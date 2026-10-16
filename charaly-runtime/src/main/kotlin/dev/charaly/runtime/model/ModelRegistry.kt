package dev.charaly.runtime.model

import dev.charaly.runtime.persistence.CharalyStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A model that exists on this device.
 *
 * There is exactly ONE kind of installed model. An imported GGUF and a downloaded
 * GGUF are the same record; the only difference is [origin]. Two parallel systems
 * ("imported" versus "downloaded") is exactly the kind of thing that makes a model
 * library feel broken, so Charaly does not have one.
 */
@Serializable
data class InstalledModel(
    val id: String,
    val displayName: String,
    /** Absolute path inside app storage. Charaly copies files it owns. */
    val absolutePath: String,
    val sizeBytes: Long = 0L,
    val origin: ModelOrigin = ModelOrigin.IMPORTED,
    val catalogId: String = "",
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 2048,
    val parameterCount: Long = 0L,
    val sha256: String = "",
    val verified: Boolean = false,
    val installedAtEpochMs: Long = 0L,
    val profiles: List<ModelProfile> = emptyList(),
    /** Compatibility findings from the last load attempt. */
    val compatibility: ModelCompatibility = ModelCompatibility(),
) {
    init {
        require(id.isNotBlank()) { "InstalledModel needs an id" }
        require(displayName.isNotBlank()) { "InstalledModel $id needs a displayName" }
        require(absolutePath.isNotBlank()) { "InstalledModel $id needs a path" }
    }

    val sizeLabel: String get() = formatBytes(sizeBytes)

    /** Catalog entries only carry a suggestion; the file header is the truth. */
    fun effectiveContextTokens(): Int = when {
        contextLength > 0 -> minOf(contextLength, ModelBindingResolver.DEVICE_CONTEXT_CEILING)
        else -> 2048
    }

    /** Which built-in profiles are sensible for this model. */
    fun suggestedProfiles(): List<ModelProfile> {
        val small = parameterCount in 1..3_500_000_000L
        return buildList {
            add(ModelProfileLibrary.resolve(ModelProfileLibrary.BALANCED))
            add(ModelProfileLibrary.resolve(ModelProfileLibrary.CINEMATIC))
            if (small) add(ModelProfileLibrary.resolve(ModelProfileLibrary.TINY_CONTEXT))
            if (parameterCount >= 3_000_000_000L) add(ModelProfileLibrary.resolve(ModelProfileLibrary.CREATIVE))
        }
    }

    /** Compatibility summary in user language, never a raw error string. */
    fun deviceVerdict(availableRamBytes: Long): ModelDeviceVerdict {
        if (sizeBytes > 0 && availableRamBytes > 0 && sizeBytes > availableRamBytes) {
            return ModelDeviceVerdict(
                level = DeviceFitLevel.TOO_LARGE,
                message = "Bu model, cihazdaki kullanılabilir belleğin üzerine çıkabilir.",
            )
        }
        if (compatibility.loadFailed) {
            return ModelDeviceVerdict(
                level = DeviceFitLevel.UNSUPPORTED,
                message = compatibility.failureReason.ifBlank { "Bu model dosyası yüklenemedi." },
            )
        }
        if (architecture.isNotBlank() && !KnownArchitectures.ALL.contains(architecture)) {
            return ModelDeviceVerdict(
                level = DeviceFitLevel.UNTESTED,
                message = "Tanınmayan mimari ($architecture). Charaly yine de yüklemeyi deneyecek.",
            )
        }
        return ModelDeviceVerdict(level = DeviceFitLevel.READY, message = "Bu cihazda hazır.")
    }
}

@Serializable
enum class ModelOrigin {
    /** Copied in from a file the user picked with the system picker. */
    IMPORTED,

    /** Fetched from the catalog. */
    DOWNLOADED,
}

@Serializable
data class ModelCompatibility(
    val loadFailed: Boolean = false,
    val failureReason: String = "",
    val lastLoadedAtEpochMs: Long = 0L,
    val chatTemplateDetected: String = "",
    /**
     * The bundled engine's verdict for this model's architecture, recorded at
     * import/verify/install time. Drives the "VALID GGUF but ENGINE UNSUPPORTED"
     * presentation. Null until the first header read; a blank default is never
     * serialised as a real verdict.
     */
    val engineSupport: EngineSupport? = null,
) {
    companion object {
        val UNKNOWN = ModelCompatibility()
    }
}

enum class DeviceFitLevel { READY, UNTESTED, TOO_LARGE, UNSUPPORTED }

data class ModelDeviceVerdict(val level: DeviceFitLevel, val message: String)

/**
 * The installation pipeline, as an explicit state machine.
 *
 * ```
 * CATALOG -> DOWNLOADING/IMPORTED -> VERIFYING -> REGISTERED -> CHECKING -> READY
 *                |                       |           |
 *                +------> FAILED <-------+-----------+
 *                                                 CANCELLED
 * ```
 * The UI renders this enum. It never invents progress.
 */
@Serializable
enum class InstallState {
    /** Only in the catalog. No file on the device. */
    CATALOG,

    DOWNLOADING,
    PAUSED,

    /** The file exists but has not been verified yet. */
    IMPORTED,

    VERIFYING,
    REGISTERED,
    CHECKING,
    READY,

    FAILED,
    CANCELLED,
    ;

    val isActionable: Boolean
        get() = this == DOWNLOADING || this == PAUSED || this == VERIFYING || this == CHECKING

    val isUsable: Boolean
        get() = this == READY || this == REGISTERED || this == CHECKING
}

/**
 * What the UI can offer for one model. Derived, never stored twice.
 */
data class ModelActions(
    val canUse: Boolean,
    val canDelete: Boolean,
    val canVerify: Boolean,
    val canDownload: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val disabledReason: String = "",
) {
    companion object {
        /** Every operation is off, with one honest reason. */
        fun unavailable(reason: String) = ModelActions(
            canUse = false,
            canDelete = false,
            canVerify = false,
            canDownload = false,
            canResume = false,
            canCancel = false,
            disabledReason = reason,
        )
    }
}

/**
 * The model registry port.
 *
 * The app implements it over app-private storage; tests implement it over
 * [dev.charaly.runtime.persistence.InMemoryCharalyStorage]. The registry is the
 * single source of truth for "which models do I have", independent of whether
 * llama.cpp has one loaded right now.
 */
interface ModelRegistry {
    suspend fun list(): List<InstalledModel>
    suspend fun get(id: String): InstalledModel?
    suspend fun register(model: InstalledModel): InstalledModel
    suspend fun update(model: InstalledModel): InstalledModel
    suspend fun remove(id: String)
    suspend fun setActive(id: String?)
    suspend fun activeId(): String?
    suspend fun profilesFor(modelId: String): List<ModelProfile>
    suspend fun saveProfile(modelId: String, profile: ModelProfile): List<ModelProfile>
    suspend fun removeProfile(modelId: String, profileId: String): List<ModelProfile>
}

/**
 * Registry state, including the active selection.
 *
 * Kept as one document so "which model is selected" has exactly one answer.
 */
@Serializable
data class ModelRegistryState(
    val models: List<InstalledModel> = emptyList(),
    val activeModelId: String = "",
) {
    companion object {
        val EMPTY = ModelRegistryState()
    }
}

/** JSON-document registry, on the same storage port as stories. No server, no SQLite. */
class JsonModelRegistry(
    private val storage: CharalyStorage,
    private val json: Json = defaultRegistryJson,
) : ModelRegistry {

    private var cached: ModelRegistryState? = null

    override suspend fun list(): List<InstalledModel> = state().models.sortedBy { it.displayName.lowercase() }

    override suspend fun get(id: String): InstalledModel? = state().models.firstOrNull { it.id == id }

    override suspend fun register(model: InstalledModel): InstalledModel {
        val current = state()
        val existing = current.models.indexOfFirst { it.id == model.id }
        val models = if (existing >= 0) {
            current.models.toMutableList().apply { this[existing] = model }
        } else {
            current.models + model
        }
        // First registration activates the model: a fresh install should be usable.
        val active = current.activeModelId.ifBlank { model.id }
        return persist(current.copy(models = models, activeModelId = active)).let { model }
    }

    override suspend fun update(model: InstalledModel): InstalledModel = register(model)

    override suspend fun remove(id: String) {
        val current = state()
        val models = current.models.filterNot { it.id == id }
        val active = if (current.activeModelId == id) models.firstOrNull()?.id.orEmpty() else current.activeModelId
        persist(current.copy(models = models, activeModelId = active))
    }

    override suspend fun setActive(id: String?) {
        val current = state()
        persist(current.copy(activeModelId = id.orEmpty()))
    }

    override suspend fun activeId(): String? = state().activeModelId.takeIf { it.isNotBlank() }

    override suspend fun profilesFor(modelId: String): List<ModelProfile> =
        get(modelId)?.profiles.orEmpty()

    override suspend fun saveProfile(modelId: String, profile: ModelProfile): List<ModelProfile> {
        val model = get(modelId) ?: return emptyList()
        val profiles = (model.profiles.filterNot { it.id == profile.id } + profile)
            .sortedBy { it.name.lowercase() }
        update(model.copy(profiles = profiles))
        return profiles
    }

    override suspend fun removeProfile(modelId: String, profileId: String): List<ModelProfile> {
        val model = get(modelId) ?: return emptyList()
        val profiles = model.profiles.filterNot { it.id == profileId }
        update(model.copy(profiles = profiles))
        return profiles
    }

    private suspend fun state(): ModelRegistryState {
        cached?.let { return it }
        val raw = storage.read(DOCUMENT)
        val decoded = raw?.let { runCatching { json.decodeFromString(ModelRegistryState.serializer(), it) }.getOrNull() }
        val loaded = decoded ?: ModelRegistryState.EMPTY
        cached = loaded
        return loaded
    }

    private suspend fun persist(state: ModelRegistryState): ModelRegistryState {
        storage.write(DOCUMENT, json.encodeToString(ModelRegistryState.serializer(), state))
        cached = state
        return state
    }

    companion object {
        const val DOCUMENT = "models/registry.json"

        val defaultRegistryJson: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
}

/**
 * Architectures llama.cpp is expected to handle well.
 *
 * This is a *warning* list, not a gate: an unknown architecture still gets loaded,
 * because refusing it would block legitimate new models.
 */
object KnownArchitectures {
    val ALL: Set<String> = setOf(
        "Qwen3", "Qwen2", "Qwen", "Llama", "Gemma", "Gemma2", "Gemma3",
        "Mistral", "Mixtral", "Phi3", "Phi4", "Falcon", "DeepSeek2", "Command-R",
    )
}

/**
 * A registry over an in-memory list. Used by tests and by previews.
 */
class InMemoryModelRegistry(
    initial: List<InstalledModel> = emptyList(),
    private val activeId: String = initial.firstOrNull()?.id.orEmpty(),
) : ModelRegistry {

    private val models = initial.associateBy { it.id }.toMutableMap()
    private var active: String = activeId

    override suspend fun list(): List<InstalledModel> = models.values.sortedBy { it.displayName.lowercase() }

    override suspend fun get(id: String): InstalledModel? = models[id]

    override suspend fun register(model: InstalledModel): InstalledModel {
        models[model.id] = model
        if (active.isBlank()) active = model.id
        return model
    }

    override suspend fun update(model: InstalledModel): InstalledModel = register(model)

    override suspend fun remove(id: String) {
        models.remove(id)
        if (active == id) active = models.keys.firstOrNull().orEmpty()
    }

    override suspend fun setActive(id: String?) {
        active = id.orEmpty()
    }

    override suspend fun activeId(): String? = active.takeIf { it.isNotBlank() }

    override suspend fun profilesFor(modelId: String): List<ModelProfile> = models[modelId]?.profiles.orEmpty()

    override suspend fun saveProfile(modelId: String, profile: ModelProfile): List<ModelProfile> {
        val model = models[modelId] ?: return emptyList()
        val profiles = (model.profiles.filterNot { it.id == profile.id } + profile)
            .sortedBy { it.name.lowercase() }
        models[modelId] = model.copy(profiles = profiles)
        return profiles
    }

    override suspend fun removeProfile(modelId: String, profileId: String): List<ModelProfile> {
        val model = models[modelId] ?: return emptyList()
        val profiles = model.profiles.filterNot { it.id == profileId }
        models[modelId] = model.copy(profiles = profiles)
        return profiles
    }
}