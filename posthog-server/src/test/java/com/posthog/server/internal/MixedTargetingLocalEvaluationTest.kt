package com.posthog.server.internal

import com.posthog.internal.PostHogApi
import com.posthog.server.PostHogUnresolvedFlagReason
import com.posthog.server.createMockHttp
import com.posthog.server.createTestConfig
import com.posthog.server.jsonResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flags whose conditions set their own `aggregation_group_type_index`, so one flag can target
 * both people and groups.
 */
internal class MixedTargetingLocalEvaluationTest {
    private var mockServer: MockWebServer? = null
    private var featureFlags: PostHogFeatureFlags? = null

    @AfterTest
    fun tearDown() {
        featureFlags?.shutDown()
        mockServer?.shutdown()
    }

    @Test
    fun `a mixed flag matches on either its group condition or its person condition`() {
        load(mixedFlag(key = "mixed", flagAggregation = "null", conditions = listOf(enterpriseCompany(), proPerson())))

        assertEquals(true, evaluate("mixed", groupProperties = mapOf("company" to mapOf("tier" to "enterprise"))))
        assertEquals(true, evaluate("mixed", personProperties = mapOf("plan" to "pro")))
        assertEquals(
            false,
            evaluate(
                "mixed",
                personProperties = mapOf("plan" to "free"),
                groupProperties = mapOf("company" to mapOf("tier" to "startup")),
            ),
        )
    }

    @Test
    fun `a group condition buckets its rollout on the group key`() {
        // "mixed-rollout" hashes "acme" to 0.148 and "user-2" to 0.963.
        load(mixedFlag(key = "mixed-rollout", flagAggregation = "null", conditions = listOf(anyCompany(rollout = 30))))

        assertEquals(true, evaluate("mixed-rollout", distinctId = "user-2", groupProperties = mapOf("company" to emptyMap())))
    }

    @Test
    fun `a condition that omits its aggregation inherits the flag-level group aggregation`() {
        load(mixedFlag(key = "legacy-group", flagAggregation = "0", conditions = listOf(enterpriseCompany(aggregation = null))))

        assertEquals(true, evaluate("legacy-group", groupProperties = mapOf("company" to mapOf("tier" to "enterprise"))))
    }

    @Test
    fun `an explicit person aggregation overrides a flag-level group aggregation`() {
        load(mixedFlag(key = "person-override", flagAggregation = "0", conditions = listOf(proPerson())))

        assertEquals(true, evaluate("person-override", personProperties = mapOf("plan" to "pro")))
    }

    @Test
    fun `the matching condition picks the identity for the variant`() {
        // "mixed-variant" hashes "user-2" to variant "test" and "acme" to "control".
        load(
            mixedFlag(
                key = "mixed-variant",
                flagAggregation = "null",
                conditions = listOf(enterpriseCompany(), proPerson()),
                multivariate = EVEN_SPLIT,
            ),
        )

        assertEquals(
            "test",
            evaluate("mixed-variant", distinctId = "user-2", groups = emptyMap(), personProperties = mapOf("plan" to "pro")),
        )
        assertEquals(
            "control",
            evaluate(
                "mixed-variant",
                distinctId = "user-2",
                groupProperties = mapOf("company" to mapOf("tier" to "enterprise")),
            ),
        )
    }

    @Test
    fun `a group condition without its group stays inconclusive when no other condition matches`() {
        load(mixedFlag(key = "mixed", flagAggregation = "null", conditions = listOf(enterpriseCompany(), proPerson())))

        val result = evaluateLocally("mixed", groups = emptyMap(), personProperties = mapOf("plan" to "free"))

        assertEquals(null, result.flags["mixed"])
        assertEquals(PostHogUnresolvedFlagReason.MISSING_CONTEXT, result.unresolvedFlags["mixed"])
    }

    private fun enterpriseCompany(aggregation: String? = "0"): String =
        condition(aggregation, """{ "key": "tier", "value": "enterprise", "operator": "exact", "type": "group", "group_type_index": 0 }""")

    private fun anyCompany(rollout: Int): String = condition("0", properties = null, rollout = rollout)

    private fun proPerson(): String = condition("null", """{ "key": "plan", "value": "pro", "operator": "exact", "type": "person" }""")

    /** A null [aggregation] omits the field; the string `"null"` sets it to JSON null. */
    private fun condition(
        aggregation: String?,
        properties: String?,
        rollout: Int = 100,
    ): String {
        val aggregationField = aggregation?.let { """, "aggregation_group_type_index": $it""" }.orEmpty()
        return """{ "properties": [${properties.orEmpty()}], "rollout_percentage": $rollout$aggregationField }"""
    }

    private fun mixedFlag(
        key: String,
        flagAggregation: String,
        conditions: List<String>,
        multivariate: String = "null",
    ): String =
        """
        {
                "flags": [
                        {
                                "id": 1,
                                "name": "$key",
                                "key": "$key",
                                "active": true,
                                "filters": {
                                        "aggregation_group_type_index": $flagAggregation,
                                        "groups": [${conditions.joinToString(",")}],
                                        "multivariate": $multivariate
                                },
                                "version": 1
                        }
                ],
                "group_type_mapping": { "0": "company" },
                "cohorts": {}
        }
        """.trimIndent()

    private fun load(definitions: String) {
        val server = createMockHttp(jsonResponse(definitions)).also { mockServer = it }
        val config = createTestConfig(host = server.url("/").toString())
        val loaded = CountDownLatch(1)
        featureFlags =
            PostHogFeatureFlags(
                config,
                PostHogApi(config),
                60000,
                100,
                localEvaluation = true,
                personalApiKey = "test-personal-key",
                onFeatureFlags = { loaded.countDown() },
            )
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
    }

    private fun evaluateLocally(
        key: String,
        distinctId: String = "user-1",
        groups: Map<String, String> = mapOf("company" to "acme"),
        personProperties: Map<String, Any?> = emptyMap(),
        groupProperties: Map<String, Map<String, Any?>> = emptyMap(),
    ): EvaluateFlagsResult =
        featureFlags!!.evaluateFlags(
            distinctId = distinctId,
            groups = groups,
            personProperties = personProperties,
            groupProperties = groupProperties,
            flagKeys = listOf(key),
            onlyEvaluateLocally = true,
            disableGeoip = false,
        )

    /** The flag's value: its variant, or whether it is enabled. Fails if it didn't resolve locally. */
    private fun evaluate(
        key: String,
        distinctId: String = "user-1",
        groups: Map<String, String> = mapOf("company" to "acme"),
        personProperties: Map<String, Any?> = emptyMap(),
        groupProperties: Map<String, Map<String, Any?>> = emptyMap(),
    ): Any {
        val result = evaluateLocally(key, distinctId, groups, personProperties, groupProperties)
        val flag = result.flags[key] ?: error("$key did not resolve locally: ${result.unresolvedFlags[key]}")
        return flag.variant ?: flag.enabled
    }

    private companion object {
        const val EVEN_SPLIT: String =
            """{ "variants": [{ "key": "control", "rollout_percentage": 50 }, { "key": "test", "rollout_percentage": 50 }] }"""
    }
}
