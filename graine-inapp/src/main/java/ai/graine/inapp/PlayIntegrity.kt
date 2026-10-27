package ai.graine.inapp

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * EVERYTHING OF PLAY INTEGRITY THE SDK TOUCHES, IN ONE FILE (APP-IDENTITY.md
 * §3.5). The SDK is compiled against Play Integrity and ships without it (a
 * compile-only dependency): an app that proves itself ("Require a verified
 * app") adds `implementation("com.google.android.play:integrity:1.4.0")`, and
 * an app that does not never loads the class below — PlayIntegrity.prover()
 * looks for the library's class first. No other file names a Play Integrity
 * class (a test holds every file to that).
 */

internal object PlayIntegrity {
    /** The class whose presence says Play Integrity is in the app. */
    const val FACTORY_CLASS = "com.google.android.play.core.integrity.IntegrityManagerFactory"

    /** Play Integrity is in the app: its factory class can be found. Initialises nothing of it. */
    fun present(loader: ClassLoader? = PlayIntegrity::class.java.classLoader, className: String = FACTORY_CLASS): Boolean = try {
        Class.forName(className, false, loader)
        true
    } catch (t: Throwable) {
        false
    }

    /** The prover, when Play Integrity is in the app; null otherwise — and nothing of it was loaded. */
    fun prover(context: Context): IntegrityProver? =
        if (present()) PlayIntegrityProver(context.applicationContext ?: context) else null
}

/**
 * A classic Play Integrity request (§3.5): the challenge as its nonce, and
 * the cloud project number when the agent's settings name one. Made only once
 * [PlayIntegrity.present] found the library.
 */
internal class PlayIntegrityProver(private val context: Context) : IntegrityProver {
    override fun token(nonce: String, cloudProjectNumber: Long?, timeoutMs: Long): IntegrityAnswer {
        val answer = AtomicReference<IntegrityAnswer>()
        val done = CountDownLatch(1)
        try {
            val request = IntegrityTokenRequest.builder().setNonce(nonce)
            if (cloudProjectNumber != null) request.setCloudProjectNumber(cloudProjectNumber)
            IntegrityManagerFactory.create(context).requestIntegrityToken(request.build())
                .addOnSuccessListener { response ->
                    val token = response.token()
                    answer.set(if (token.isNullOrEmpty()) IntegrityAnswer.Unavailable("no token") else IntegrityAnswer.Token(token))
                    done.countDown()
                }
                .addOnFailureListener { e ->
                    val code = (e as? IntegrityServiceException)?.errorCode
                    answer.set(IntegrityErrors.classify(code, e.javaClass.simpleName))
                    done.countDown()
                }
        } catch (t: Throwable) {
            // Part of the library missing, or a request it refused to make: no proof from this device.
            return IntegrityAnswer.Unavailable(t.javaClass.simpleName)
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return IntegrityAnswer.Unreachable("timeout")
        return answer.get() ?: IntegrityAnswer.Unreachable("no answer")
    }
}
