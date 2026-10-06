package dev.charaly.runtime.model

import dev.charaly.runtime.persistence.CharalyStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Measured benchmarks, one per installed model.
 *
 * ## Why this is a document and not a column
 *
 * A benchmark is a *fact about this device*, not about the model file: the same GGUF runs
 * at different speeds on different phones. Storing it on the model would make it travel
 * with the file into any backup and then be quoted as a measurement on a device that
 * never ran it. So it lives in its own document, keyed by model id, and it is dropped
 * whenever the model it describes is removed.
 *
 * Same storage port as everything else in Charaly - app-private JSON files. No database,
 * no server.
 */
interface BenchmarkStore {
    /** Every recorded measurement, newest first. */
    suspend fun all(): List<BenchmarkRecord>

    suspend fun forModel(modelId: String): BenchmarkRecord?

    /** Records a run, replacing any previous one for the same model. */
    suspend fun record(record: BenchmarkRecord)

    /** Forgets a model's measurement. Called when the model is deleted. */
    suspend fun forget(modelId: String)

    /** Forgets everything. Used when a device-level change makes old numbers meaningless. */
    suspend fun clear()
}

@Serializable
private data class BenchmarkDocument(
    val records: List<BenchmarkRecord> = emptyList(),
)

class JsonBenchmarkStore(
    private val storage: CharalyStorage,
    private val json: Json = defaultJson,
) : BenchmarkStore {

    private var cached: BenchmarkDocument? = null

    override suspend fun all(): List<BenchmarkRecord> = document().records.sortedByDescending {
        it.benchmark.measuredAtEpochMs
    }

    override suspend fun forModel(modelId: String): BenchmarkRecord? =
        document().records.firstOrNull { it.benchmark.modelId == modelId }

    override suspend fun record(record: BenchmarkRecord) {
        val current = document()
        // One measurement per model, always the most recent. Keeping a history would let
        // a stale reading from a colder run be compared with a warm one, and a user would
        // have no way to tell which was which.
        val records = current.records.filterNot { it.benchmark.modelId == record.benchmark.modelId } + record
        persist(BenchmarkDocument(records))
    }

    override suspend fun forget(modelId: String) {
        val current = document()
        if (current.records.none { it.benchmark.modelId == modelId }) return
        persist(BenchmarkDocument(current.records.filterNot { it.benchmark.modelId == modelId }))
    }

    override suspend fun clear() {
        cached = BenchmarkDocument()
        storage.delete(DOCUMENT)
    }

    private suspend fun document(): BenchmarkDocument {
        cached?.let { return it }
        val raw = storage.read(DOCUMENT)
        val decoded = raw
            ?.let { runCatching { json.decodeFromString(BenchmarkDocument.serializer(), it) }.getOrNull() }
        val loaded = decoded ?: BenchmarkDocument()
        cached = loaded
        return loaded
    }

    private suspend fun persist(document: BenchmarkDocument) {
        storage.write(DOCUMENT, json.encodeToString(BenchmarkDocument.serializer(), document))
        cached = document
    }

    companion object {
        const val DOCUMENT = "models/benchmarks.json"

        val defaultJson: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}

/** In-memory store for tests and previews. */
class InMemoryBenchmarkStore(
    initial: List<BenchmarkRecord> = emptyList(),
) : BenchmarkStore {

    private val records = initial.associateBy { it.benchmark.modelId }.toMutableMap()

    override suspend fun all(): List<BenchmarkRecord> = records.values.sortedByDescending {
        it.benchmark.measuredAtEpochMs
    }

    override suspend fun forModel(modelId: String): BenchmarkRecord? = records[modelId]

    override suspend fun record(record: BenchmarkRecord) {
        records[record.benchmark.modelId] = record
    }

    override suspend fun forget(modelId: String) {
        records.remove(modelId)
    }

    override suspend fun clear() {
        records.clear()
    }
}