@file:JvmName("GraineVerificationFlow")

package ai.graine.inapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * GraineInApp.verification AS A STATEFLOW, for apps that collect flows.
 *
 * The SDK depends on no coroutines at run time: this file is compiled against
 * kotlinx-coroutines and ships without it (a compile-only dependency), and is
 * the only one that names a coroutine type. An app that reads
 * verificationFlow collects flows, so it has coroutines already; an app that
 * does not never loads this file. It sits in a folder of its own so that the
 * call engine copied into the React Native and Flutter packages, which have
 * no coroutines, leaves it out.
 */

/**
 * [GraineInApp.verification] as a StateFlow: its value now, then each change,
 * for `collectAsState()` or a `collect` of your own. Needs
 * kotlinx-coroutines in the app — every app that collects flows has it.
 * Without coroutines, use [GraineInApp.addVerificationListener].
 */
val GraineInApp.verificationFlow: StateFlow<GraineVerification>
    get() {
        @Suppress("UNCHECKED_CAST")
        (verificationFlowHolder as? MutableStateFlow<GraineVerification>)?.let { return it.asStateFlow() }
        synchronized(this) {
            @Suppress("UNCHECKED_CAST")
            (verificationFlowHolder as? MutableStateFlow<GraineVerification>)?.let { return it.asStateFlow() }
            val flow = MutableStateFlow(verification)
            addVerificationListener { v -> flow.value = v }
            // A change between the first read and the listener is not missed.
            flow.value = verification
            verificationFlowHolder = flow
            return flow.asStateFlow()
        }
    }
