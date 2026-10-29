package com.spoolstudio.app.data.remote.spoolman

import android.util.Log
import com.spoolstudio.app.data.local.TagStorageSettings
import com.google.gson.JsonElement
import com.spoolstudio.app.domain.models.CreateFilamentRequest
import com.spoolstudio.app.domain.models.CreateSpoolRequest
import com.spoolstudio.app.domain.models.CreateVendorRequest
import com.spoolstudio.app.domain.models.FilamentSpool
import com.spoolstudio.app.domain.models.SpoolmanExtraField
import com.spoolstudio.app.domain.models.SpoolmanExtraFieldRequest
import com.spoolstudio.app.domain.models.SpoolmanFilament
import com.spoolstudio.app.domain.models.SpoolmanSpool
import com.spoolstudio.app.domain.models.SpoolmanVendor
import com.spoolstudio.app.domain.models.SpoolStudioFilamentFields
import com.spoolstudio.app.domain.models.UpdateVendorRequest
import com.spoolstudio.app.domain.models.encodeSpoolmanExtraString
import com.spoolstudio.app.domain.models.formatCardUidsForSpoolman
import com.spoolstudio.app.domain.models.normalizeCardUid
import com.spoolstudio.app.domain.models.normalizeSpoolLinkFilamentFields
import com.spoolstudio.app.domain.models.parseCardUids
import com.spoolstudio.app.domain.models.spoolLinkSpoolmanFields
import com.spoolstudio.app.domain.models.splitLegacyMaterialAndVariant
import com.spoolstudio.app.domain.models.stringValue
import com.spoolstudio.app.domain.models.toRequestExtraMap
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface SpoolmanApi {
    @GET("api/v1/info")
    suspend fun getInfo(): Response<SpoolmanInfo>

    @POST("api/v1/spool/{id}/tag")
    suspend fun linkTag(@Path("id") id: Int, @Body request: TagLinkRequest): Response<ResponseBody>

    @DELETE("api/v1/spool/{id}/tag/{uid}")
    suspend fun unlinkTag(@Path("id") id: Int, @Path("uid") uid: String): Response<ResponseBody>

    @GET("api/v1/spool")
    suspend fun getSpools(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String? = null,
        @Query("allow_archived") allowArchived: Boolean? = null,
        @Query("tag") tag: String? = null
    ): Response<List<SpoolmanSpool>>

    @GET("api/v1/spool/{id}")
    suspend fun getSpool(@Path("id") id: Int): Response<SpoolmanSpool>

    @GET("api/v1/filament/{id}")
    suspend fun getFilament(@Path("id") id: Int): Response<SpoolmanFilament>

    @GET("api/v1/vendor")
    suspend fun getVendors(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String? = null
    ): Response<List<SpoolmanVendor>>

    @GET("api/v1/filament")
    suspend fun getFilaments(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String? = null,
        @Query("tag") tag: String? = null
    ): Response<List<SpoolmanFilament>>

    @GET("api/v1/field/{entityType}")
    suspend fun getExtraFields(
        @Path("entityType") entityType: String
    ): Response<List<SpoolmanExtraField>>

    @POST("api/v1/field/{entityType}/{key}")
    suspend fun addOrUpdateExtraField(
        @Path("entityType") entityType: String,
        @Path("key") key: String,
        @Body request: SpoolmanExtraFieldRequest
    ): Response<ResponseBody>

    @POST("api/v1/vendor")
    suspend fun createVendor(@Body request: CreateVendorRequest): Response<SpoolmanVendor>

    @PATCH("api/v1/vendor/{id}")
    suspend fun updateVendor(
        @Path("id") id: Int,
        @Body request: UpdateVendorRequest
    ): Response<SpoolmanVendor>

    @POST("api/v1/filament")
    suspend fun createFilament(@Body request: CreateFilamentRequest): Response<SpoolmanFilament>

    @PATCH("api/v1/filament/{id}")
    suspend fun updateFilament(
        @Path("id") id: Int,
        @Body request: Map<String, @JvmSuppressWildcards Any?>
    ): Response<SpoolmanFilament>

    @POST("api/v1/spool")
    suspend fun createSpool(@Body request: CreateSpoolRequest): Response<SpoolmanSpool>

    @PATCH("api/v1/spool/{id}")
    suspend fun updateSpool(
        @Path("id") id: Int,
        @Body request: Map<String, @JvmSuppressWildcards Any?>
    ): Response<SpoolmanSpool>

    @DELETE("api/v1/spool/{id}")
    suspend fun deleteSpool(@Path("id") id: Int): Response<ResponseBody>
}

data class SpoolmanCatalog(
    val spools: List<FilamentSpool>,
    val vendorNames: List<String>,
    val materialNames: List<String>,
    val variantNames: List<String>,
    val locationNames: List<String>,
    val cardUidFieldSpoolCount: Int = 0,
    val cardUidFieldKeys: List<String> = emptyList(),
    val materialModifierFieldAvailable: Boolean = false
)

data class SpoolmanLegacyFilamentConversion(
    val filamentId: Int,
    val name: String,
    val vendorName: String,
    val spoolIds: List<Int>,
    val currentMaterial: String,
    val currentVariant: String,
    val currentMaterialModifier: String,
    val targetMaterial: String,
    val targetVariant: String,
    val targetMaterialModifier: String
) {
    val affectedSpoolCount: Int
        get() = spoolIds.size

    val currentLabel: String
        get() {
            val (_, embeddedVariant) = splitLegacyMaterialAndVariant(currentMaterial)
            return buildList {
                add(currentMaterial)
                currentMaterialModifier.takeIf { it.isNotBlank() }?.let { add(it) }
                currentVariant
                    .takeIf { it.isNotBlank() }
                    .takeUnless { it.equals("Basic", ignoreCase = true) }
                    .takeUnless { it.equals(embeddedVariant, ignoreCase = true) }
                    ?.let { add(it) }
            }.joinToString(" / ")
        }

    val targetLabel: String
        get() = buildList {
            add(targetMaterial)
            targetMaterialModifier.takeIf { it.isNotBlank() }?.let { add(it) }
            targetVariant.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" / ")
}

class SpoolmanService(private val baseUrl: String) {
    internal var nativeOnly = TagStorageSettings.nativeOnly(baseUrl)
    private var nativeTagSupport: Boolean? = null

    suspend fun hasNativeTags(): Boolean {
        nativeTagSupport?.let { return it }
        val response = api.getInfo()
        val supported = if (response.code() == 404) false else supportsNativeTags(response.checked("Server version")?.version)
        nativeTagSupport = supported
        return supported
    }

    suspend fun requireTagMode(): Boolean {
        val supported = hasNativeTags()
        check(!nativeOnly || supported) { "Native tags only requires Spoolman 0.27.0 or newer. Change Tag storage in Settings for this server." }
        return supported
    }
    private var cachedCatalog: SpoolmanCatalog? = null
    private var lastFetchTime = 0L
    private val cacheValidityMs = 30_000L
    private val connectionConfig = spoolmanConnectionConfig(baseUrl)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply {
            connectionConfig.authorizationHeader?.let { authHeader ->
                addInterceptor { chain ->
                    chain.proceed(
                        chain.request()
                            .newBuilder()
                            .header("Authorization", authHeader)
                            .build()
                    )
                }
            }
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    internal var api = Retrofit.Builder()
        .baseUrl(connectionConfig.retrofitBaseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SpoolmanApi::class.java)

    companion object {
        private const val PAGE_SIZE = 100
        private const val SPOOL_ENTITY_TYPE = "spool"
        private const val FILAMENT_ENTITY_TYPE = "filament"
        private const val CARD_UIDS_FIELD_KEY = "card_uids"
        private const val VARIANT_FIELD_KEY = "variant"
        const val MATERIAL_MODIFIER_FIELD_KEY = "material_modifier"
    }

    private fun normalizeText(value: String?): String = value?.trim().orEmpty()
    private fun normalizeUpper(value: String?): String = normalizeText(value).uppercase()
    private fun normalizeHex(value: String?): String? =
        normalizeText(value).removePrefix("#").ifBlank { null }?.uppercase()

    private fun weightsEqual(first: Float?, second: Float?): Boolean =
        (first == null && second == null) ||
            (first != null && second != null && kotlin.math.abs(first - second) <= 0.01f)

    suspend fun getCatalog(sortBy: String? = null, forceRefresh: Boolean = false): SpoolmanCatalog {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedCatalog?.let { cached ->
                if (now - lastFetchTime < cacheValidityMs) return cached
            }
        }

        val spoolFetchResult = fetchAllSpools(sortBy)
        val vendors = fetchAllVendors()
        val filaments = fetchAllFilaments()
        val filamentTags = filaments.flatMap { it.tags.orEmpty().map { tag -> tag.uid } }.toSet()
        val spools = spoolFetchResult.spools.map { spool -> spool.copy(cardUids = spool.cardUids.filterNot { it in filamentTags }) }
        val filamentExtraFields = fetchExtraFields(FILAMENT_ENTITY_TYPE)
        val cardUidFieldKeys = spoolFetchResult.rawSpools
            .flatMap { it.extra.cardUidKeys() }
            .distinct()
            .sorted()

        val vendorNames = (vendors.map { it.name } + spools.map { it.brand })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val materialNames = (filaments.map { spoolLinkFields(it).material } + spools.map { it.material })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val variantNames = (filaments.map { spoolLinkFields(it).variant } + spools.map { it.variant })
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val locationNames = spools.mapNotNull { it.location }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        return SpoolmanCatalog(
            spools = spools,
            vendorNames = vendorNames,
            materialNames = materialNames,
            variantNames = variantNames,
            locationNames = locationNames,
            cardUidFieldSpoolCount = spoolFetchResult.rawSpools.count { it.extra.cardUidKeys().isNotEmpty() },
            cardUidFieldKeys = cardUidFieldKeys,
            materialModifierFieldAvailable = hasMaterialModifierField(filamentExtraFields)
        ).also {
            cachedCatalog = it
            lastFetchTime = now
        }
    }

    suspend fun hasFilamentMaterialModifierField(): Boolean =
        hasMaterialModifierField(fetchExtraFields(FILAMENT_ENTITY_TYPE))

    suspend fun createFilamentMaterialModifierField(): Boolean {
        ensureExtraTextField(
            entityType = FILAMENT_ENTITY_TYPE,
            key = MATERIAL_MODIFIER_FIELD_KEY,
            name = "Material modifier",
            order = 20,
            errorContext = "Material modifier field"
        )
        cachedCatalog = null
        return hasFilamentMaterialModifierField()
    }

    suspend fun ensurePaxx12SpoolLinkFields() {
        if (!nativeOnly) ensureExtraTextField(
            entityType = SPOOL_ENTITY_TYPE,
            key = CARD_UIDS_FIELD_KEY,
            name = "Card UIDs",
            order = 1,
            defaultValue = encodeSpoolmanExtraString(""),
            errorContext = "Paxx12 Card UIDs field"
        )
        ensureExtraTextField(
            entityType = FILAMENT_ENTITY_TYPE,
            key = VARIANT_FIELD_KEY,
            name = "Variant",
            order = 1,
            defaultValue = encodeSpoolmanExtraString(""),
            errorContext = "Paxx12 Variant field"
        )
    }

    suspend fun findLegacyFilamentConversions(): List<SpoolmanLegacyFilamentConversion> {
        val rawSpools = fetchAllRawSpools(includeArchived = true)
        return rawSpools
            .groupBy { it.filament.id }
            .mapNotNull { (_, spools) ->
                val filament = spools.firstOrNull()?.filament ?: return@mapNotNull null
                buildLegacyFilamentConversion(
                    filament = filament,
                    spoolIds = spools.mapNotNull { it.id }.distinct().sorted()
                )
            }
            .sortedWith(compareBy<SpoolmanLegacyFilamentConversion> { it.vendorName.lowercase() }
                .thenBy { it.name.lowercase() }
                .thenBy { it.filamentId })
    }

    suspend fun convertLegacyFilaments(filamentIds: Set<Int>): Int {
        if (filamentIds.isEmpty()) return 0
        val candidates = findLegacyFilamentConversions()
            .filter { it.filamentId in filamentIds }
        if (candidates.isEmpty()) return 0

        ensurePaxx12SpoolLinkFields()
        if (candidates.any { it.targetMaterialModifier.isNotBlank() }) {
            createFilamentMaterialModifierField()
        }

        candidates.forEach { candidate ->
            val response = api.getFilament(candidate.filamentId)
            if (!response.isSuccessful) {
                val errorText = response.errorBody()?.string()
                throw IllegalStateException(
                    "Filament ${candidate.filamentId} could not be loaded for conversion (${response.code()}): $errorText"
                )
            }
            val filament = response.body()
                ?: throw IllegalStateException("Filament ${candidate.filamentId} conversion response was empty")
            val extra = filament.extra.toRequestExtraMap()
            extra[VARIANT_FIELD_KEY] = encodeSpoolmanExtraString(candidate.targetVariant.ifBlank { "Basic" })
            if (candidate.targetMaterialModifier.isBlank()) {
                extra.remove(MATERIAL_MODIFIER_FIELD_KEY)
            } else {
                extra[MATERIAL_MODIFIER_FIELD_KEY] = encodeSpoolmanExtraString(candidate.targetMaterialModifier)
            }

            val updateResponse = api.updateFilament(
                candidate.filamentId,
                mapOf(
                    "material" to candidate.targetMaterial,
                    "extra" to extra
                )
            )
            if (!updateResponse.isSuccessful) {
                val errorText = updateResponse.errorBody()?.string()
                throw IllegalStateException(
                    "Filament ${candidate.filamentId} could not be converted (${updateResponse.code()}): $errorText"
                )
            }
        }

        cachedCatalog = null
        return candidates.size
    }

    private fun buildLegacyFilamentConversion(
        filament: SpoolmanFilament,
        spoolIds: List<Int>
    ): SpoolmanLegacyFilamentConversion? {
        val rawMaterial = normalizeText(filament.material)
        if (rawMaterial.isBlank()) return null

        val (legacyMaterial, legacyVariant) = splitLegacyMaterialAndVariant(rawMaterial)
        val currentVariant = filament.extra.stringValue(VARIANT_FIELD_KEY)
            ?: legacyVariant.ifBlank { "Basic" }
        val currentModifier = filament.extra.stringValue(MATERIAL_MODIFIER_FIELD_KEY).orEmpty()
        val fields = spoolLinkSpoolmanFields(
            material = legacyMaterial,
            variant = currentVariant,
            materialModifier = currentModifier,
            allowMaterialModifier = true
        )

        val needsConversion =
            !rawMaterial.equals(fields.material, ignoreCase = true) ||
                !currentVariant.equals(fields.variant, ignoreCase = true) ||
                !currentModifier.equals(fields.materialModifier, ignoreCase = true)

        if (!needsConversion) return null

        return SpoolmanLegacyFilamentConversion(
            filamentId = filament.id,
            name = filament.name.ifBlank { "Filament #${filament.id}" },
            vendorName = filament.vendor?.name.orEmpty().ifBlank { "Unknown brand" },
            spoolIds = spoolIds,
            currentMaterial = rawMaterial,
            currentVariant = currentVariant,
            currentMaterialModifier = currentModifier,
            targetMaterial = fields.material,
            targetVariant = fields.variant.ifBlank { "Basic" },
            targetMaterialModifier = fields.materialModifier
        )
    }

    private suspend fun fetchExtraFields(entityType: String): List<SpoolmanExtraField> {
        return try {
            val response = api.getExtraFields(entityType)
            if (response.isSuccessful) response.body().orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun ensureExtraTextField(
        entityType: String,
        key: String,
        name: String,
        order: Int,
        defaultValue: String? = null,
        errorContext: String
    ) {
        val fields = fetchExtraFields(entityType)
        if (fields.any { field ->
                field.key.equals(key, ignoreCase = true) &&
                    field.field_type.equals("text", ignoreCase = true)
            }
        ) {
            return
        }

        val response = api.addOrUpdateExtraField(
            entityType = entityType,
            key = key,
            request = SpoolmanExtraFieldRequest(
                name = name,
                order = order,
                field_type = "text",
                default_value = defaultValue
            )
        )
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("$errorContext could not be created (${response.code()}): $errorText")
        }
        cachedCatalog = null
    }

    private fun hasMaterialModifierField(fields: List<SpoolmanExtraField>): Boolean =
        fields.any { field ->
            field.key.equals(MATERIAL_MODIFIER_FIELD_KEY, ignoreCase = true) &&
                field.field_type.equals("text", ignoreCase = true)
        }

    suspend fun getFilaments(sortBy: String? = null, forceRefresh: Boolean = false): List<FilamentSpool> {
        return getCatalog(sortBy, forceRefresh).spools
    }

    private data class SpoolFetchResult(
        val rawSpools: List<SpoolmanSpool>,
        val spools: List<FilamentSpool>
    )

    private suspend fun fetchAllSpools(sortBy: String? = null): SpoolFetchResult {
        val rawSpools = mutableListOf<SpoolmanSpool>()
        var offset = 0
        while (true) {
            Log.d("SpoolmanService", "Fetching spools: offset=$offset, limit=$PAGE_SIZE, sort=$sortBy")
            val response = api.getSpools(PAGE_SIZE, offset, sortBy, allowArchived = true)
            val rawBatch = response.checked("Load spools").orEmpty()
            val batch = rawBatch.map { FilamentSpool.fromSpoolman(it) }
            rawSpools.addAll(rawBatch)
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        requireTagMode()
        val nativeOwners = rawSpools.flatMap { spool ->
            spool.tags.orEmpty().map { it.uid to spool.id } + spool.filament.tags.orEmpty().map { it.uid to null }
        }.toMap()
        val resolved = rawSpools.map { spool ->
            val legacy = if (nativeOnly) emptyList() else parseCardUids(spool.extra.stringValue("card_uids") ?: spool.extra.stringValue("card_uid"))
            FilamentSpool.fromSpoolman(spool).copy(cardUids = (spool.tags.orEmpty().map { it.uid } +
                legacy.filter { it !in nativeOwners || nativeOwners[it] == spool.id }).distinct())
        }
        return SpoolFetchResult(rawSpools = rawSpools, spools = resolved)
    }

    private suspend fun fetchAllVendors(): List<SpoolmanVendor> {
        val vendors = mutableListOf<SpoolmanVendor>()
        var offset = 0
        while (true) {
            val response = api.getVendors(PAGE_SIZE, offset, "name:asc")
            if (!response.isSuccessful) break
            val batch = response.body().orEmpty()
            vendors.addAll(batch)
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return vendors
    }

    private suspend fun fetchAllFilaments(): List<SpoolmanFilament> {
        val filaments = mutableListOf<SpoolmanFilament>()
        var offset = 0
        while (true) {
            val response = api.getFilaments(PAGE_SIZE, offset, "name:asc")
            if (!response.isSuccessful) break
            val batch = response.body().orEmpty()
            filaments.addAll(batch)
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return filaments
    }

    private fun spoolLinkFields(filament: SpoolmanFilament): SpoolStudioFilamentFields {
        val normalized = normalizeSpoolLinkFilamentFields(
            material = filament.material,
            variant = filament.extra.stringValue("variant")
        )
        return spoolLinkSpoolmanFields(
            material = normalized.material,
            variant = normalized.variant,
            materialModifier = filament.extra.stringValue("material_modifier")
        )
    }

    suspend fun countSpoolsUsingFilament(filamentId: Int): Int {
        var count = 0
        var offset = 0
        while (true) {
            val response = api.getSpools(PAGE_SIZE, offset, null)
            if (!response.isSuccessful) break
            val batch = response.body().orEmpty()
            count += batch.count { it.filament.id == filamentId }
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return count
    }

    suspend fun findFilamentBySpoolId(spoolId: String): FilamentSpool? {
        val id = spoolId.toIntOrNull() ?: return null
        cachedCatalog?.spools?.find { it.id == id }?.let { return it }
        return try {
            val response = api.getSpool(id)
            if (response.isSuccessful) response.body()?.let {
                FilamentSpool.fromSpoolman(it).let { spool ->
                    if (nativeOnly) spool.copy(cardUids = spool.nativeTagUids) else spool
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun findSpoolByLotNr(lotNr: String, forceRefresh: Boolean = false): FilamentSpool? {
        val normalizedLotNr = lotNr.trim()
        if (normalizedLotNr.isBlank()) return null

        return getCatalog(forceRefresh = forceRefresh).spools.firstOrNull { spool ->
            spool.lotNr?.trim()?.equals(normalizedLotNr, ignoreCase = false) == true
        }
    }

    suspend fun existsLotNr(lotNr: String, forceRefresh: Boolean = false): Boolean {
        return findSpoolByLotNr(lotNr, forceRefresh) != null
    }

    suspend fun createOrFindVendor(name: String, emptySpoolWeight: Float? = null): SpoolmanVendor {
        val normalizedName = normalizeText(name)
        val existing = fetchAllVendors().firstOrNull { it.name.equals(normalizedName, ignoreCase = true) }
        if (existing != null) {
            val id = existing.id
            return if (
                id != null &&
                emptySpoolWeight != null &&
                !weightsEqual(existing.empty_spool_weight, emptySpoolWeight)
            ) {
                updateVendorEmptySpoolWeight(existing, emptySpoolWeight)
            } else {
                existing
            }
        }

        val response = api.createVendor(
            CreateVendorRequest(
                name = normalizedName,
                comment = "Spool Studio",
                empty_spool_weight = emptySpoolWeight ?: 180f
            )
        )
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Vendor could not be created (${response.code()}): $errorText")
        }
        cachedCatalog = null
        return response.body() ?: throw IllegalStateException("Vendor response was empty")
    }

    private suspend fun updateVendorEmptySpoolWeight(
        vendor: SpoolmanVendor,
        emptySpoolWeight: Float
    ): SpoolmanVendor {
        val id = vendor.id ?: return vendor
        val response = api.updateVendor(
            id,
            UpdateVendorRequest(
                name = vendor.name,
                comment = vendor.comment,
                empty_spool_weight = emptySpoolWeight
            )
        )
        if (!response.isSuccessful) {
            Log.w("SpoolStudio", "Vendor empty spool weight update failed (${response.code()})")
            return vendor
        }
        cachedCatalog = null
        return response.body() ?: vendor.copy(empty_spool_weight = emptySpoolWeight)
    }

    suspend fun createOrFindFilament(
        name: String,
        material: String,
        variant: String,
        materialModifier: String = "",
        allowMaterialModifier: Boolean = true,
        vendorId: Int,
        colorHex: String?,
        nozzleTemp: Int?,
        bedTemp: Int?,
        spoolWeight: Float? = null
    ): SpoolmanFilament {
        val normalizedName = normalizeText(name)
        val fields = spoolLinkSpoolmanFields(
            material = material,
            variant = variant,
            materialModifier = materialModifier,
            allowMaterialModifier = allowMaterialModifier
        )
        val normalizedMaterial = fields.material
        val normalizedVariant = fields.variant
        val normalizedMaterialModifier = fields.materialModifier
        val normalizedColorHex = normalizeHex(colorHex)

        val existing = fetchAllFilaments().firstOrNull { filament ->
            val existingFields = spoolLinkFields(filament)
            filament.vendor?.id == vendorId &&
                existingFields.material.equals(normalizedMaterial, ignoreCase = true) &&
                existingFields.variant.equals(normalizedVariant, ignoreCase = true) &&
                existingFields.materialModifier.equals(normalizedMaterialModifier, ignoreCase = true) &&
                normalizeHex(filament.color_hex) == normalizedColorHex &&
                normalizeText(filament.name).equals(normalizedName, ignoreCase = true)
        }
        if (existing != null) {
            val canonical = existing.material == normalizedMaterial &&
                existing.extra.stringValue("variant") == normalizedVariant &&
                existing.extra.stringValue("material_modifier").isNullOrBlank()
            return if (canonical && (spoolWeight == null || weightsEqual(existing.spool_weight, spoolWeight))) {
                existing
            } else {
                updateFilament(
                    id = existing.id,
                    name = normalizedName,
                    material = normalizedMaterial,
                    variant = normalizedVariant,
                    materialModifier = normalizedMaterialModifier,
                    allowMaterialModifier = allowMaterialModifier,
                    vendorId = vendorId,
                    colorHex = normalizedColorHex,
                    nozzleTemp = nozzleTemp,
                    bedTemp = bedTemp,
                    spoolWeight = spoolWeight
                )
            }
        }

        val request = CreateFilamentRequest(
            name = normalizedName.ifBlank { normalizedColorHex ?: "Unknown" },
            material = normalizedMaterial,
            vendor_id = vendorId,
            color_hex = normalizedColorHex,
            settings_extruder_temp = nozzleTemp,
            settings_bed_temp = bedTemp,
            density = defaultDensity(FilamentSpool.splitMaterialAndVariant(normalizedMaterial).first),
            diameter = 1.75f,
            weight = 1000f,
            spool_weight = spoolWeight,
            price = 0.0f,
            comment = "Spool Studio",
            extra = buildFilamentExtra(normalizedVariant, normalizedMaterialModifier)
        )
        val response = api.createFilament(request)
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Filament could not be created (${response.code()}): $errorText")
        }
        cachedCatalog = null
        return response.body() ?: throw IllegalStateException("Filament response was empty")
    }

    suspend fun updateFilament(
        id: Int,
        name: String,
        material: String,
        variant: String,
        materialModifier: String = "",
        allowMaterialModifier: Boolean = true,
        vendorId: Int,
        colorHex: String?,
        nozzleTemp: Int?,
        bedTemp: Int?,
        spoolWeight: Float? = null
    ): SpoolmanFilament {
        val request = mutableMapOf<String, Any?>()
        val fields = spoolLinkSpoolmanFields(
            material = material,
            variant = variant,
            materialModifier = materialModifier,
            allowMaterialModifier = allowMaterialModifier
        )
        val existing = api.getFilament(id).checked("Load filament before updating variant")
            ?: throw IllegalStateException("Filament response was empty; variant was not changed")
        val existingExtra = existing.extra.toRequestExtraMap()
        request["name"] = normalizeText(name).ifBlank { "Unknown" }
        request["material"] = fields.material
        request["vendor_id"] = vendorId
        request["color_hex"] = normalizeHex(colorHex)
        request["settings_extruder_temp"] = nozzleTemp
        request["settings_bed_temp"] = bedTemp
        spoolWeight?.let { request["spool_weight"] = it }
        request["comment"] = "Spool Studio"
        existingExtra["variant"] = encodeSpoolmanExtraString(fields.variant)
        if (fields.materialModifier.isBlank()) {
            existingExtra.remove("material_modifier")
        } else {
            existingExtra["material_modifier"] = encodeSpoolmanExtraString(fields.materialModifier)
        }
        request["extra"] = existingExtra

        val response = api.updateFilament(id, request)
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Filament could not be updated (${response.code()}): $errorText")
        }
        cachedCatalog = null
        return response.body() ?: throw IllegalStateException("Filament update response was empty")
    }

    suspend fun createSpool(
        filamentId: Int,
        lotNr: String?,
        location: String? = null,
        remainingWeight: Float? = null,
        comment: String? = null,
        owner: String? = null
    ): SpoolmanSpool {
        val cleanOwner = owner?.trim().orEmpty()
        if (cleanOwner.isNotBlank()) ensureOwnerField()
        val response = api.createSpool(
            CreateSpoolRequest(
                filament_id = filamentId,
                lot_nr = lotNr,
                location = location,
                remaining_weight = remainingWeight ?: 1000f,
                comment = comment ?: "Created by Spool Studio",
                extra = cleanOwner.takeIf { it.isNotBlank() }?.let {
                    mapOf("owner" to encodeSpoolmanExtraString(it))
                }
            )
        )
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Spool could not be created (${response.code()}): $errorText")
        }
        cachedCatalog = null
        return response.body() ?: throw IllegalStateException("Spool response was empty")
    }

    suspend fun updateSpool(
        id: Int,
        filamentId: Int? = null,
        lotNr: String? = null,
        location: String? = null,
        remainingWeight: Float? = null,
        comment: String? = null,
        owner: String? = null
    ): SpoolmanSpool {
        val request = mutableMapOf<String, Any?>()
        filamentId?.let { request["filament_id"] = it }
        request["lot_nr"] = lotNr
        location?.let { request["location"] = it }
        remainingWeight?.let { request["remaining_weight"] = it }
        request["comment"] = comment
        if (owner != null) {
            val current = api.getSpool(id).checked("Load spool before updating owner")
                ?: throw IllegalStateException("Spool response was empty; owner was not changed")
            val cleanOwner = owner.trim()
            if (cleanOwner != current.extra.stringValue("owner").orEmpty()) {
                if (cleanOwner.isNotBlank()) ensureOwnerField()
                val extra = current.extra.toRequestExtraMap()
                extra["owner"] = encodeSpoolmanExtraString(cleanOwner)
                request["extra"] = extra
            }
        }

        val response = api.updateSpool(id, request)
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Spool could not be updated (${response.code()}): $errorText")
        }
        cachedCatalog = null
        return response.body() ?: throw IllegalStateException("Spool update response was empty")
    }

    suspend fun findSpoolByCardUid(cardUid: String): FilamentSpool? {
        if (cardUid.isBlank()) return null
        val normalizedUid = canonicalTagUid(cardUid)

        if (requireTagMode()) {
            val spools = api.getSpools(PAGE_SIZE, allowArchived = true, tag = normalizedUid)
                .checked("Find native spool tag").orEmpty()
            check(spools.size <= 1 && spools.all { it.tags.orEmpty().any { tag -> tag.uid == normalizedUid } }) {
                "Server returned an invalid native tag result; no spool selected."
            }
            spools.firstOrNull()?.let {
                val spool = FilamentSpool.fromSpoolman(it)
                return if (nativeOnly) spool.copy(cardUids = spool.nativeTagUids) else spool
            }
            val filaments = api.getFilaments(PAGE_SIZE, tag = normalizedUid).checked("Find native filament tag").orEmpty()
            check(filaments.size <= 1 && filaments.all { it.tags.orEmpty().any { tag -> tag.uid == normalizedUid } }) {
                "Server returned an invalid native filament tag result."
            }
            filaments.firstOrNull()?.let { throw FilamentTagException(it.id, it.name) }
            if (nativeOnly) return null
        }
        val matches = fetchAllRawSpools(includeArchived = true).filter { spool ->
            parseCardUids(spool.extra.stringValue("card_uids") ?: spool.extra.stringValue("card_uid"))
                .any { it == normalizedUid }
        }
        check(matches.size <= 1) { "UID is assigned to multiple legacy spools. Resolve the duplicate before selecting a spool." }
        return matches.firstOrNull()?.let { FilamentSpool.fromSpoolman(it) }
    }

    private suspend fun ensureOwnerField() {
        ensureExtraTextField(
            entityType = SPOOL_ENTITY_TYPE,
            key = "owner",
            name = "Owner",
            order = 10,
            defaultValue = encodeSpoolmanExtraString(""),
            errorContext = "Spool owner field"
        )
    }

    suspend fun assignAndVerifyCardUidToSpool(spoolId: Int, cardUid: String, moveExisting: Boolean = false) {
        assignCardUidToSpool(spoolId, cardUid, moveExisting)
        verifyCardUidLink(spoolId, cardUid)
    }

    suspend fun verifyCardUidLink(spoolId: Int, cardUid: String) {
        val uid = canonicalTagUid(cardUid)
        val nativeSupported = requireTagMode()
        val spool = api.getSpool(spoolId).checked("Verify tag link")
            ?: throw IllegalStateException("Tag link could not be verified: empty spool response")
        check(spool.id == spoolId) { "Tag link could not be verified: server returned another spool" }
        if (nativeSupported) check(spool.tags.orEmpty().any { canonicalTagUid(it.uid) == uid }) {
            "UID $uid is missing from spool #$spoolId's native tags. Retry Link scanned UID."
        }
        if (!nativeOnly) check(uid in parseCardUids(spool.extra.stringValue("card_uids"))) {
            "UID $uid is missing from spool #$spoolId's Card UIDs. Paxx12 cannot resolve this tag yet. Retry Link scanned UID."
        }
    }

    suspend fun assignCardUidToSpool(spoolId: Int, cardUid: String, moveExisting: Boolean = false) {
        val normalizedUid = canonicalTagUid(cardUid)
        if (requireTagMode()) {
            var response = api.linkTag(spoolId, TagLinkRequest(normalizedUid))
            if (response.code() == 409) {
                val holder = com.google.gson.JsonParser().parse(response.errorBody()?.string().orEmpty()).asJsonObject
                val holderId = holder.get("spool_id")?.takeUnless { it.isJsonNull }?.asInt
                check(moveExisting && holderId != null) {
                    "UID already belongs to ${if (holderId != null) "spool #$holderId" else "a filament or another target"}. Use Link scanned UID to explicitly move a spool tag."
                }
                api.unlinkTag(holderId, normalizedUid).checked("Unlink previous native tag")?.close()
                try {
                    response = api.linkTag(spoolId, TagLinkRequest(normalizedUid))
                    response.checked("Move native tag")?.close()
                } catch (e: Exception) {
                    // Best effort restoration; never delete a new owner's link to recover.
                    val restored = runCatching { api.linkTag(holderId, TagLinkRequest(normalizedUid)).isSuccessful }.getOrDefault(false)
                    throw IllegalStateException("Tag move failed; previous link ${if (restored) "restored" else "could not be restored; check Tags in Spoolman"}.", e)
                }
            }
            response.checked("Link native tag")?.close()
            cachedCatalog = null
            if (nativeOnly) return
        }
        ensurePaxx12SpoolLinkFields()

        val targetResponse = api.getSpool(spoolId)
        if (!targetResponse.isSuccessful) {
            val errorText = targetResponse.errorBody()?.string()
            throw IllegalStateException("Spool could not be loaded for card UID update (${targetResponse.code()}): $errorText")
        }
        val target = targetResponse.body()
            ?: throw IllegalStateException("Target spool was empty; UID was not assigned")

        var changed = false
        val candidates = fetchAllRawSpools(includeArchived = true).filterNot { it.id == spoolId } + target
        candidates.forEach { spool ->
            val id = spool.id ?: return@forEach
            val currentUids = parseCardUids(spool.extra.stringValue("card_uids") ?: spool.extra.stringValue("card_uid"))
            val nextUids = if (id == spoolId) {
                (currentUids + normalizedUid).distinct()
            } else {
                currentUids.filterNot { it.equals(normalizedUid, ignoreCase = true) }
            }

            if (nextUids != currentUids) {
                val extra = spool.extra.toRequestExtraMap()
                extra.remove("card_uid")
                extra["card_uids"] = encodeSpoolmanExtraString(formatCardUidsForSpoolman(nextUids))

                val response = api.updateSpool(id, mapOf("extra" to extra))
                if (!response.isSuccessful) {
                    val errorText = response.errorBody()?.string()
                    throw IllegalStateException("Card UID could not be assigned (${response.code()}): $errorText")
                }
                changed = true
            }
        }

        if (changed) cachedCatalog = null
    }

    private suspend fun fetchAllRawSpools(includeArchived: Boolean = false): List<SpoolmanSpool> {
        val spools = mutableListOf<SpoolmanSpool>()
        var offset = 0
        while (true) {
            val response = api.getSpools(
                limit = PAGE_SIZE,
                offset = offset,
                sort = null,
                allowArchived = includeArchived.takeIf { it }
            )
            val batch = response.checked("Load all spools").orEmpty()
            spools.addAll(batch)
            if (batch.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return spools
    }

    /** Preview is read-only. Execution re-reads each spool before clearing its old field. */
    suspend fun previewTagMigration(): String {
        check(hasNativeTags()) { "Migration requires Spoolman 0.27.0 or newer." }
        val spools = fetchAllRawSpools(includeArchived = true)
        val affected = spools.filter { legacyUids(it).isNotEmpty() }
        return "Found ${affected.sumOf { legacyUids(it).size }} legacy UID entries on ${affected.size} spools (including archived).\n\n" +
            "Valid, unambiguous UIDs will be linked as native tags. Existing native owners are never changed. " +
            "Duplicate UIDs, conflicts and failed entries stay in card_uids and appear in the result. " +
            "Only confirmed native links are removed from the legacy field. Older printer firmware needs that field; keep it unless your firmware supports native tags."
    }

    private fun legacyUids(spool: SpoolmanSpool): List<String> =
        (spool.extra.stringValue("card_uids") ?: spool.extra.stringValue("card_uid"))
            ?.split(Regex("[,;\\n\\t ]+"))?.filter { it.isNotBlank() }.orEmpty()

    suspend fun migrateLegacyTags(clearLegacy: Boolean): TagMigrationReport {
        check(hasNativeTags()) { "Migration requires Spoolman 0.27.0 or newer." }
        val spools = fetchAllRawSpools(includeArchived = true)
        val counts = spools.flatMap { s -> legacyUids(s).map { runCatching { canonicalTagUid(it) }.getOrDefault(it) to s.id } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().size }
        var linked = 0
        var cleared = 0
        val issues = mutableListOf<String>()
        for (spool in spools) {
            val id = spool.id ?: continue
            val confirmed = mutableSetOf<String>()
            for (raw in legacyUids(spool)) {
                val uid = runCatching { canonicalTagUid(raw) }.getOrNull()
                if (uid == null || counts[uid] != 1) {
                    issues += "Spool #$id: invalid or duplicate UID $raw; retained."
                    continue
                }
                try {
                    val response = api.linkTag(id, TagLinkRequest(uid))
                    if (response.code() == 409) {
                        response.errorBody()?.close()
                        issues += "Spool #$id: $uid has another native owner; retained."
                        continue
                    }
                    response.checked("Migrate $uid")?.close()
                    linked++
                    confirmed += raw
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { issues += "Spool #$id / $raw: ${e.message}; retained." }
            }
            if (clearLegacy && confirmed.isNotEmpty()) {
                try {
                    val current = api.getSpool(id).checked("Re-read legacy field") ?: error("Empty spool response")
                    val nativeUids = current.tags.orEmpty().map { it.uid }.toSet()
                    val safeToClear = confirmed.filter { canonicalTagUid(it) in nativeUids }.toSet()
                    (confirmed - safeToClear).forEach { issues += "Spool #$id: $it native link changed before cleanup; retained." }
                    val remaining = legacyUids(current).filterNot { it in safeToClear }
                    if (safeToClear.isEmpty()) continue
                    val extra = current.extra.toRequestExtraMap()
                    extra.remove("card_uid")
                    extra["card_uids"] = encodeSpoolmanExtraString(remaining.joinToString(","))
                    api.updateSpool(id, mapOf("extra" to extra)).checked("Clear migrated legacy links")
                    cleared += legacyUids(current).count { it in safeToClear }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { issues += "Spool #$id: native links saved, legacy cleanup failed: ${e.message}" }
            }
        }
        cachedCatalog = null
        return TagMigrationReport(linked, cleared, issues)
    }

    suspend fun unlinkCardUid(spoolId: Int, uid: String) {
        if (requireTagMode()) {
            val response = api.unlinkTag(spoolId, uid)
            if (response.code() != 404) response.checked("Unlink tag")?.close()
        }
        // Explicit unlink removes the compatibility mirror too so it cannot reappear.
        val current = api.getSpool(spoolId).checked("Load spool") ?: error("Spool not found")
        val remaining = legacyUids(current).filterNot { normalizeCardUid(it) == normalizeCardUid(uid) }
        if (remaining.size != legacyUids(current).size) {
            val extra = current.extra.toRequestExtraMap()
            extra.remove("card_uid")
            extra["card_uids"] = encodeSpoolmanExtraString(remaining.joinToString(","))
            api.updateSpool(spoolId, mapOf("extra" to extra)).checked("Remove compatibility link")
        }
        cachedCatalog = null
    }

    suspend fun deleteSpool(id: Int) {
        val response = api.deleteSpool(id)
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Spool could not be deleted (${response.code()}): $errorText")
        }
        cachedCatalog = null
    }

    suspend fun archiveSpool(id: Int) {
        val response = api.updateSpool(id, mapOf("archived" to true))
        if (!response.isSuccessful) {
            val errorText = response.errorBody()?.string()
            throw IllegalStateException("Spool could not be archived (${response.code()}): $errorText")
        }
        cachedCatalog = null
    }

    private fun defaultDensity(material: String): Float {
        return when (material.uppercase()) {
            "PLA" -> 1.24f
            "ABS" -> 1.04f
            "PETG" -> 1.27f
            "TPU" -> 1.21f
            "ASA" -> 1.05f
            "PC" -> 1.20f
            "NYLON" -> 1.14f
            "PVA" -> 1.23f
            "HIPS" -> 1.03f
            else -> 1.24f
        }
    }

    private fun buildFilamentExtra(variant: String, materialModifier: String): Map<String, String> =
        buildMap {
            put("variant", encodeSpoolmanExtraString(variant))
            if (materialModifier.isNotBlank()) {
                put("material_modifier", encodeSpoolmanExtraString(materialModifier))
            }
        }
}

private fun Map<String, JsonElement>?.cardUidKeys(): List<String> {
    if (this == null) return emptyList()
    return keys.filter { key ->
        key.equals("card_uid", ignoreCase = true) ||
            key.equals("card_uids", ignoreCase = true)
    }
}
