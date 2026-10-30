package com.parachord.android.di

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.parachord.shared.di.sharedModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Builds the app's real Koin graph and resolves every definition (#271).
 *
 * A wiring mistake only shows at runtime: `singleOf(::X)` resolves every
 * constructor parameter from the graph and ignores Kotlin defaults, so a
 * defaulted or lambda parameter with no definition crashes the app at launch
 * (bc47c23, #305). Unit tests build classes directly and never see it. This
 * fails on any missing definition anywhere in a resolution's cause chain.
 *
 * Only missing definitions fail the test. A definition that throws for
 * another reason in Robolectric is reported but tolerated, so the test
 * tracks wiring, not the test environment.
 *
 * Koin's static `Module.verify()` doesn't work here: it inspects the
 * constructors of classes built inside `single { }` lambdas (flagging every
 * explicit binding written to keep a default), and it crashes reflecting
 * Ktor's `HttpClient` constructor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KoinModuleVerifyTest {

    @After fun tearDown() {
        stopKoin()
    }

    @Test fun `every definition in the app graph resolves`() {
        val missing = missingDefinitions(sharedModule, androidModule)
        assertEquals("missing Koin definitions:\n${missing.joinToString("\n")}", emptyList<String>(), missing)
    }

    @Test fun `a singleOf class with a defaulted lambda parameter is caught`() {
        val missing = missingDefinitions(module { singleOf(::ClockedThing) })
        assertTrue("expected the Function0 parameter to be reported: $missing", missing.single().contains("Function0"))
    }

    /** The bc47c23 shape: an injectable clock with a default `singleOf` ignores. */
    class ClockedThing(val nowMs: () -> Long = { 0L })

    /**
     * Resolve every definition in [modules]; return one line per definition
     * that failed because some definition is missing. ViewModels get a
     * [SavedStateHandle], which their screen supplies at runtime.
     */
    @OptIn(KoinInternalApi::class)
    private fun missingDefinitions(vararg modules: Module): List<String> {
        val koin = startKoin {
            androidContext(RuntimeEnvironment.getApplication())
            modules(*modules)
        }.koin
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }.distinct()
        val missing = mutableListOf<String>()
        for (def in definitions) {
            try {
                koin.get<Any>(def.primaryType, def.qualifier) { parametersOf(SavedStateHandle()) }
            } catch (t: Throwable) {
                val noDefinition = generateSequence(t) { it.cause }.firstOrNull { it is NoDefinitionFoundException }
                if (noDefinition != null) {
                    missing += "${def.primaryType.qualifiedName}: ${noDefinition.message}"
                } else {
                    println("KoinModuleVerifyTest: ${def.primaryType.qualifiedName} didn't build in Robolectric (tolerated): $t")
                }
            }
        }
        return missing
    }
}
