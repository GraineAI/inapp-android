package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WHO THE USER IS (contract §1.2–1.4): which trait value reaches Graine and
 * which never does, the install id, the memory choice kept per user, and the
 * shape every request carries. The server derives the key; the SDK only has
 * to keep what can never be a person off the wire — and never drop a real one.
 */
class IdentityTest {
    private val vectors: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/identity-vectors.json").exists() && !File(dir, "contract/identity-vectors.json").exists()) {
            dir = dir.parentFile
        }
        val f = listOf(File(dir, "sdk/contract/identity-vectors.json"), File(dir, "contract/identity-vectors.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/identity-vectors.json missing")
        JSONObject(f.readText())
    }

    private fun userKeys(): List<JSONObject> = vectors.getJSONArray("user_keys").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun raw(v: Any?): Any? = if (v == JSONObject.NULL) null else v

    @Test fun theVectorsHaveSomethingToProve() {
        assertTrue(userKeys().size >= 20)
    }

    @Test fun everyVectorNormalisesExactlyAsTheServerDoes() {
        for (v in userKeys()) {
            val field = if (v.isNull("normalised_field")) continue else v.getString("normalised_field")
            val expected = if (v.isNull("normalised_value")) null else v.getString("normalised_value")
            assertEquals("vector $v", expected, IdentityRules.normalisedValue(field, raw(v.opt("value"))))
        }
    }

    @Test fun anIdentityIsSentExactlyWhenTheServerWouldKeyIt() {
        for (v in userKeys()) {
            val field = IdentityRules.field(v.opt("field")) ?: continue
            val value = raw(v.opt("value"))
            val sent = IdentityRules.pick(mapOf(field to value), field)
            val keyed = !v.isNull("normalised_value")
            assertEquals("vector $v", keyed, sent != null)
            // What the device sends is what the app gave, trimmed — the server normalises it.
            if (sent != null) assertEquals("vector $v", java.text.Normalizer.normalize(value.toString(), java.text.Normalizer.Form.NFC).trim(IdentityRules::isServerSpace), sent)
        }
        // What can never be a person never leaves the device: null, booleans, placeholders, masked values, too long.
        for (never in listOf(null, true, false, "null", "Undefined", "", "  ", "[EMAIL]", "[PHONE]", "guest", "0", "n/a", "x".repeat(257))) {
            assertNull("$never", IdentityRules.pick(mapOf("user_id" to never), "user_id"))
        }
        assertNull("an e-mail with no domain names nobody", IdentityRules.pick(mapOf("email" to "asha@"), "email"))
        assertNull("nor does a five-digit phone", IdentityRules.pick(mapOf("phone" to "12345"), "phone"))
    }

    @Test fun aWholeNumberIsItsDigitsAndAFractionOrAnObjectIsNobody() {
        assertEquals("4242", IdentityRules.rawText(4242))
        assertEquals("98765432100", IdentityRules.rawText(98765432100L))
        // A bridge from JavaScript hands 4242 over as 4242.0: still the user's id.
        assertEquals("4242", IdentityRules.rawText(4242.0))
        assertNull(IdentityRules.rawText(4.5))
        assertNull(IdentityRules.rawText(4.5f))
        assertNull(IdentityRules.rawText(mapOf("id" to "x")))
        assertNull(IdentityRules.rawText(listOf("x")))
        assertNull(IdentityRules.rawText(JSONObject.NULL))
        assertNull(IdentityRules.rawText(true))
    }

    @Test fun fieldsNormaliseAsTheVectorsSay() {
        for (v in userKeys()) {
            val expected = if (v.isNull("normalised_field")) null else v.getString("normalised_field")
            assertEquals("field ${v.opt("field")}", expected, IdentityRules.field(v.opt("field")))
        }
        assertNull(IdentityRules.field(null))
        assertNull(IdentityRules.field(7))
        assertNull(IdentityRules.field("a".repeat(41)))
    }

    @Test fun theTraitIsTheOneTheFieldNames() {
        val traits = mapOf("name" to "Asha", "user_id" to "P-1234", "Patient_ID" to "MRN-1")
        assertEquals("P-1234", IdentityRules.pick(traits, "user_id"))
        // The same key with other capitals still names the trait.
        assertEquals("MRN-1", IdentityRules.pick(traits, "patient_id"))
        assertEquals("Patient_ID", IdentityRules.keyFor(traits, "patient_id"))
        assertNull("no such trait: no identity", IdentityRules.pick(traits, "email"))
        assertNull(IdentityRules.pick(null, "user_id"))
        assertNull(IdentityRules.pick(traits, null))
    }

    @Test fun theSessionNamesTheFieldOrSaysNothing() {
        assertEquals(SessionIdentity("email", 3), IdentityRules.parse(JSONObject().put("field", "Email").put("devicesPerUser", 3)))
        assertEquals("no field: the default", SessionIdentity("user_id", 2), IdentityRules.parse(JSONObject()))
        assertEquals("devices clamp to 1–5", 5, IdentityRules.parse(JSONObject().put("devicesPerUser", 40))!!.devicesPerUser)
        assertNull("absent block: an older server", IdentityRules.parse(null))
        assertEquals("a field that cannot be one is the default", "user_id", IdentityRules.parse(JSONObject().put("field", "patient-id"))!!.field)
    }

    @Test fun everyRequestCarriesTheSameEnvelope() {
        val o = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", false, "Asia/Kolkata").into(JSONObject())
        assertEquals("P-1234", o.getJSONObject("identity").getString("value"))
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", o.getString("installId"))
        assertFalse(o.getBoolean("memory"))
        assertEquals("Asia/Kolkata", o.getString("timezone"))
        val anonymous = IdentityEnvelope(null, "3f2504e0-4f89-11d3-9a0c-0305e82c3301", true, null).into(JSONObject())
        assertFalse("no identity, no key", anonymous.has("identity"))
        assertTrue(anonymous.getBoolean("memory"))
        // The routes' common part: the install, and the identity only with one — no memory, no zone.
        val who = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", false, "Asia/Kolkata").who(JSONObject())
        assertEquals(setOf("installId", "identity"), who.keys().asSequence().toSet())
        // A log line never carries who the user is.
        assertFalse(IdentityEnvelope("asha@example.com", null, true, null).toString().contains("asha"))
    }

    private fun tempStore(): Pair<File, DeviceStore> {
        val dir = Files.createTempDirectory("graine-device").toFile()
        val f = File(dir, "device.json")
        return f to DeviceStore(f)
    }

    @Test fun theInstallIdIsMadeOnceAndKept() {
        val (file, store) = tempStore()
        val id = store.installId()
        assertTrue("a lower-case UUIDv4", Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(id))
        assertTrue(InstallIds.valid(id))
        assertEquals(id, store.installId())
        assertEquals("read back by the next launch", id, DeviceStore(file).installId())
        // A value that could not be one is replaced, never sent.
        file.writeText(JSONObject().put("installId", "not-an-id!").toString())
        val replaced = DeviceStore(file).installId()
        assertNotEquals("not-an-id!", replaced)
        assertTrue(InstallIds.valid(replaced))
    }

    @Test fun aStoreThatCannotWriteStillAnswersForThisLaunch() {
        val store = DeviceStore(null)
        val id = store.installId()
        assertEquals(id, store.installId())
        store.pushToken = "tok"
        assertEquals("tok", store.pushToken)
        store.pushToken = " "
        assertNull("blank is no token", store.pushToken)
    }

    @Test fun theMemoryChoiceIsKeptPerUserAndBounded() {
        val (file, store) = tempStore()
        assertNull(store.memoryFor("email", "asha@example.com"))
        store.rememberMemory("email", "asha@example.com", false)
        assertEquals(false, store.memoryFor("email", "asha@example.com"))
        assertEquals("filed under the normalised value", false, store.memoryFor("email", " Asha@Example.com "))
        assertNull("the same value under another field is another user", store.memoryFor("user_id", "asha@example.com"))
        assertNull("another user chose nothing", store.memoryFor("email", "ravi@example.com"))
        store.rememberMemory("email", "asha@example.com", true)
        assertEquals("the newest choice wins", true, DeviceStore(file).memoryFor("email", "asha@example.com"))
        // The raw identity is never written down: only a one-way hash of the field and the normalised value.
        assertFalse(file.readText().contains("asha"))
        assertEquals(32, IdentityRules.memoryKey("email", "asha@example.com").length)
        for (i in 0 until DeviceStore.MEMORY_CHOICES + 5) store.rememberMemory("user_id", "user-$i", false)
        assertNull("the oldest choices are let go", store.memoryFor("email", "asha@example.com"))
        assertEquals(false, store.memoryFor("user_id", "user-${DeviceStore.MEMORY_CHOICES + 4}"))
        assertEquals(DeviceStore.MEMORY_CHOICES, JSONArray(JSONObject(file.readText()).getString("memory")).length())
    }

    @Test fun theIdentityResolvesWhenTheSessionArrivesAndRemembersTheChoice() {
        val (_, store) = tempStore()
        val state = IdentityState { store }
        state.set(mapOf("user_id" to "P-1", "email" to "asha@example.com"), memory = null)
        assertNull("no identity before the session says which trait names the user", state.value())
        assertEquals("the default field is kept out of the variables meanwhile", "user_id", state.fieldOrDefault())
        assertTrue("memory is on unless chosen otherwise", state.memory())
        state.onSession(SessionIdentity("email", 2))
        assertEquals("asha@example.com", state.value())
        state.setMemory(false)
        assertFalse(state.memory())
        // The next launch: the same user, no choice made — the last one stands.
        val next = IdentityState { store }
        next.onSession(SessionIdentity("email", 2))
        next.set(mapOf("email" to "asha@example.com"), memory = null)
        assertFalse(next.memory())
        next.set(mapOf("email" to "ravi@example.com"), memory = null)
        assertTrue("another user is on by default", next.memory())
        next.onSession(null)
        assertEquals("a session without a block is the default field", "user_id", next.fieldOrDefault())
        assertNull(next.value())
        val env = state.envelope()
        assertEquals("asha@example.com", env.value)
        assertFalse(env.memory)
        assertTrue(InstallIds.valid(env.installId))
    }

    @Test fun theIdentifyingTraitNeverReachesThePrompt() {
        val vars = VariableStore()
        vars.setIdentity(mapOf("user_id" to "P-1234", "name" to "Asha", "plan" to "gold"))
        assertEquals(mapOf("name" to "Asha", "plan" to "gold"), vars.identity())
        assertFalse(vars.merged().containsKey("user_id"))
        vars.setIdentityField("email")
        vars.setIdentity(mapOf("Email" to "asha@example.com", "user_id" to "P-1234"))
        assertEquals("the session's field, whatever its capitals", mapOf("user_id" to "P-1234"), vars.identity())
    }
}
