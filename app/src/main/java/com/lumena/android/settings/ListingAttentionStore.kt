package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.listing.ListingAttentionPolicy
import com.lumena.android.listing.ListingAttentionState
import com.lumena.android.listing.ListingNoticeExtractor
import com.lumena.android.listing.ListingRawCapture
import com.lumena.android.listing.ListingRecord
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * App-private persistence for the listing-attention skill: recent listings
 * (for rarity and reposts), the owner's labels and the learned weights.
 *
 * Stores only notifications from watched marketplace apps; private chat is
 * dropped before anything is written.
 */
object ListingAttentionStore {
    const val FILE_NAME = "lumena_listing_attention_v1.json"
    private val lock = StateVaultLock.monitor
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(ListingAttentionState::class.java)

    fun load(context: Context): ListingAttentionState = synchronized(lock) {
        val file = atomicFile(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return@synchronized ListingAttentionState()
        }
        val parsed = try {
            adapter.fromJson(file.openRead().bufferedReader().use { it.readText() })
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Listing-attention state is unreadable; refusing to replace the owner's labels.",
                failure
            )
        }
        requireNotNull(parsed) { "Listing-attention state is empty or corrupt." }
    }

    /** Scores the listings in one notification; returns the newly seen ones. */
    fun ingest(context: Context, raw: ListingRawCapture): List<ListingRecord> = synchronized(lock) {
        if (!ListingNoticeExtractor.isWatchedSource(raw.source) || ListingNoticeExtractor.isPrivateMessage(raw)) {
            return@synchronized emptyList()
        }
        var state = ListingAttentionPolicy.capture(load(context), raw)
        val added = mutableListOf<ListingRecord>()
        ListingNoticeExtractor.extract(raw).forEach { notice ->
            val result = ListingAttentionPolicy.ingest(state, notice)
            state = result.state
            result.record?.let { added += it }
        }
        save(context, state)
        added
    }

    /** The owner's label; the only input the skill learns from. */
    fun feedback(
        context: Context,
        recordId: String,
        useful: Boolean,
        now: Long = System.currentTimeMillis()
    ): ListingRecord? = synchronized(lock) {
        val before = load(context)
        val after = ListingAttentionPolicy.feedback(before, recordId, useful, now)
        if (after !== before) save(context, after)
        after.records.firstOrNull { it.id == recordId }
    }

    private fun save(context: Context, state: ListingAttentionState) {
        val file = atomicFile(context)
        val out = file.startWrite()
        try {
            out.write(adapter.toJson(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun atomicFile(context: Context) =
        AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
}
