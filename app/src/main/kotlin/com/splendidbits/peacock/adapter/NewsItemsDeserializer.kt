package com.splendidbits.peacock.adapter

import android.content.Context
import android.util.Log
import com.google.gson.*
import com.splendidbits.peacock.R
import com.splendidbits.peacock.enums.AssetType
import com.splendidbits.peacock.enums.ItemType
import com.splendidbits.peacock.model.Asset
import com.splendidbits.peacock.model.Batch
import com.splendidbits.peacock.model.Item
import com.splendidbits.peacock.model.Tag
import org.apache.commons.lang3.StringUtils
import java.lang.reflect.Type
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*


class NewsItemsDeserializer(appContext: Context) : JsonDeserializer<Batch> {
    val dateFormat = appContext.getString(R.string.latest_feed_date_format)
    val simpleDateFormat = SimpleDateFormat(dateFormat, Locale.US)

    // Gson extension that makes things SO CLEAR.
    fun com.google.gson.JsonObject.string(name: String?): String {
        return if (name != null && has(name) && get(name).isJsonPrimitive)
            get(name)?.asString ?: StringUtils.EMPTY else StringUtils.EMPTY
    }

    fun com.google.gson.JsonObject.boolean(name: String?): Boolean {
        return if (name != null && has(name) && get(name).isJsonPrimitive)
            get(name)?.asBoolean ?: false else false
    }

    fun com.google.gson.JsonElement.asJsonArray(): JsonArray {
        return if (isJsonArray && asJsonArray?.size() != 0) asJsonArray else JsonArray()
    }

    override fun deserialize(root: JsonElement?, typeOfT: Type, context: JsonDeserializationContext?): Batch {
        val batch = Batch()

        if (!(root?.isJsonObject ?: false)) {
            return batch
        }

        val json = root?.asJsonObject
        batch.saved = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val defaultDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        batch.updated = parseDate(json?.string("updated"), defaultDate)

        val items = json?.get("entries")?.asJsonArray() ?: JsonArray(0)
        var firstBreakingItem = -1
        var firstRegularItem = -1
        for ((itemIndex, jsonItem) in items.withIndex()) {
            Log.d(this::class.java.simpleName, "Found item index $itemIndex")

            if (jsonItem is JsonObject) {
                if (jsonItem.string("articleType").toLowerCase() == "externallink") {
                    continue
                }

                val newsItem = Item()
                newsItem.externalId = jsonItem.string("externalId")
                newsItem.itemId = if (newsItem.externalId.isNotEmpty()) newsItem.externalId else jsonItem.string("id")
                newsItem.type = ItemType.TYPE_ARTICLE
                newsItem.url = jsonItem.string("short_url")
                newsItem.title = jsonItem.string("title")
                newsItem.summary = jsonItem.string("summary")
                newsItem.content = jsonItem.string("content")
                newsItem.breaking = jsonItem.asJsonObject?.get("breaking_news")?.asBoolean ?: false
                newsItem.section = jsonItem.string("section")
                newsItem.subSection = jsonItem.string("subSection")
                newsItem.published = parseDate(jsonItem.string("published"), batch.updated)
                newsItem.publishedFirst = parseDate(jsonItem.string("published_first"), batch.updated)

                if (newsItem.breaking && firstBreakingItem > 0) {
                    firstBreakingItem = itemIndex
                } else if (!newsItem.breaking && firstRegularItem > 0) {
                    firstRegularItem = itemIndex
                }

                val jsonTags = jsonItem.get("tags")?.asJsonArray() ?: JsonArray()
                for ((tagIndex, jsonTag) in jsonTags.withIndex()) {
                    Log.d(this::class.java.simpleName, "Found tag index $tagIndex")

                    if (jsonTag is JsonObject) {
                        val itemTag = Tag()

                        itemTag.tagId = jsonTag.string("id")
                        itemTag.label = jsonTag.string("label")
                        itemTag.url = jsonTag.string("url")
                        newsItem.tags += itemTag
                    }
                }

                newsItem.assets = getAssets(jsonItem, newsItem, batch.updated)

                if (!batch.items.contains(newsItem)) {
                    batch.items += newsItem
                }
            }
        }

        // This can be replaced by the OkHTTP response stack if there is a platform specific
        // identifier for the batch such as NBC etag.
        batch.batchId = simpleDateFormat.format(batch.updated.time)
        batch.items.forEachIndexed({ index: Int, item: Item ->
            if (firstBreakingItem == -1 && item.breaking) {
                firstBreakingItem = index
                item.featured = true
            }

            if (firstRegularItem == -1 && !item.breaking) {
                firstRegularItem = index
            }
        })

        if (firstBreakingItem != firstRegularItem) {
            Collections.swap(batch.items, firstRegularItem, firstRegularItem)
        }
        return batch
    }

    private fun getAsset(jsonObject: JsonObject, defaultDate: Calendar): Asset? {
        val asset = Asset()
        asset.assetId = jsonObject.string("id")
        asset.title = jsonObject.string("title")
        asset.saved = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        asset.width = jsonObject.get("width")?.asInt ?: 0
        asset.height = jsonObject.get("height")?.asInt ?: 0
        asset.published = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        asset.published = parseDate(jsonObject.string("published"), defaultDate)

        if (jsonObject.string("type").toLowerCase() == "video") {
            asset.type = AssetType.TYPE_VIDEO
        } else {
            asset.type = AssetType.TYPE_IMAGE
            asset.url = jsonObject.string("url")
        }
        return asset
    }

    private fun getAssets(jsonItem: JsonObject, newsItem: Item, defaultDate: Calendar): List<Asset> {
        val mediaAssets = mutableListOf<Asset>()

        if (jsonItem.string("contentSrc").isNotEmpty() && jsonItem.string("videoDuration").isNotEmpty()) {
            val asset = getAsset(jsonItem, defaultDate)
            asset?.assetId = if (newsItem.externalId.isNotEmpty()) newsItem.externalId else newsItem.itemId
            asset?.duration = jsonItem.string("videoDuration")
            asset?.type = AssetType.TYPE_VIDEO
            asset?.duration = jsonItem.string("videoDuration")
            asset?.url = jsonItem.string("contentSrc").replace("http://", "https://")

            if (asset != null && newsItem.assets.none { it.assetId == asset.assetId }) {
                mediaAssets.add(asset)
            }
        }

        if (jsonItem.string("coverArt").isNotEmpty()) {
            val asset = getAsset(jsonItem, defaultDate)

            if (asset != null && asset.url.isNotEmpty()) {
                asset.type = AssetType.TYPE_IMAGE
                mediaAssets.add(asset)
            }
        }

        if (jsonItem.string("mainArt").isNotEmpty()) {
            val asset = getAsset(jsonItem, defaultDate)

            if (asset != null && asset.url.isNotEmpty()) {
                asset.type = AssetType.TYPE_IMAGE
                mediaAssets.add(asset)
            }
        }

        val jsonMediaList = jsonItem.get("mediaList")?.asJsonArray() ?: JsonArray()
        for ((assetIndex, jsonMedia) in jsonMediaList.withIndex()) {
            Log.d(this::class.java.simpleName, "Found asset index $assetIndex")

            if (jsonMedia is JsonObject && jsonMedia.string("type").toLowerCase() == "image") {
                val asset = getAsset(jsonMedia, defaultDate)
                if (asset != null && newsItem.assets.none { it.assetId == asset.assetId }) {
                    mediaAssets.add(asset)
                }
            }
        }

        for (mediaAsset in mediaAssets) {
            if (mediaAsset.width != 0 && mediaAsset.height != 0 && mediaAsset.url.isNotEmpty()) {
                val asset = getAsset(jsonItem, defaultDate)

                if (asset != null && asset.url.isNotEmpty()) {
                    asset.type = AssetType.TYPE_IMAGE
                    mediaAssets.add(0, asset)
                    break
                }
            }
        }
        return mediaAssets
    }


    private fun parseDate(dateString: String?, defaultDate: Calendar): Calendar {
        if (dateString != null && dateString.isNotEmpty()) {
            val nowCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            try {
                nowCalendar.time = simpleDateFormat.parse(dateString)

            } catch (e: ParseException) {
                Log.w(this::class.java.simpleName, "Couldn't parse date $dateString")
                return defaultDate
            }
        }
        return defaultDate
    }
}