/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Computable
import gnu.trove.THashMap
import org.rust.lang.core.crate.Crate
import org.rust.openapiext.executeUnderProgress
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

private const val PRINT_TIME_STATISTICS: Boolean = false

/** Builds [CrateDefMap] for [crates] in parallel using [pool] and with respect to dependency graph */
class DefMapMultithreadBuilder(
    private val defMapService: DefMapService,
    private val crates: List<Crate>,  // should be top sorted
    defMaps: Map<Crate, CrateDefMap>,
    private val indicator: ProgressIndicator,
    private val pool: Executor,
) {

    init {
        check(crates.isNotEmpty())
    }

    /** Values - number of dependencies for which [CrateDefMap] is not build yet */
    private val remainingDependenciesCounts: Map<Crate, AtomicInteger> = run {
        val cratesSet = crates.toSet()
        crates.associateWithTo(THashMap()) {
            val remainingDependencies = it.dependencies
                .filter { dep -> dep.crate in cratesSet }
                .size
            AtomicInteger(remainingDependencies)
        }
    }
    private val builtDefMaps: MutableMap<Crate, CrateDefMap> = ConcurrentHashMap(defMaps)

    /** We don't use [CountDownLatch] because [CompletableFuture] allows easier exception handling */
    private val remainingNumberCrates: AtomicInteger = AtomicInteger(crates.size)
    private val completableFuture: CompletableFuture<Unit> = CompletableFuture()

    /** Only for profiling */
    private val tasksTimes: MutableMap<Crate, Long> = ConcurrentHashMap()

    fun build() {
        val wallTime = measureTimeMillis {
            if (pool is SingleThreadExecutor) {
                buildSync()
            } else {
                buildAsync()
            }
        }

        printStatistics(builtDefMaps)
        timesBuildDefMaps += wallTime
        if (PRINT_TIME_STATISTICS) printTimeStatistics(wallTime)
    }

    private fun buildAsync() {
        remainingDependenciesCounts
            .filterValues { it.get() == 0 }
            .keys
            .forEach { buildDefMapAsync(it) }
        completableFuture.getWithRethrow()
    }

    private fun buildSync() {
        tryRunReadActionUnderIndicator(indicator) {
            for (crate in crates) {
                tasksTimes[crate] = measureTimeMillis {
                    doBuildDefMap(crate)
                }
            }
        }
    }

    private fun buildDefMapAsync(crate: Crate) {
        pool.execute {
            try {
                tasksTimes[crate] = measureTimeMillis {
                    tryRunReadActionUnderIndicator(indicator) {
                        doBuildDefMap(crate)
                    }
                }
            } catch (e: Throwable) {
                completableFuture.completeExceptionally(e)
                return@execute
            }
            onCrateFinished(crate)
        }
    }

    private fun doBuildDefMap(crate: Crate) {
        val crateId = crate.id ?: return
        val allDependenciesDefMaps = crate.flatDependencies
            .mapNotNull {
                // it can be null e.g. if dependency has null id
                val dependencyDefMap = builtDefMaps[it] ?: return@mapNotNull null
                it to dependencyDefMap
            }
            .toMap(THashMap())
        val defMap = buildDefMap(crate, allDependenciesDefMaps)
        val holder = defMapService.getDefMapHolder(crateId)
        holder.defMap = defMap
        holder.shouldRebuild = false
        holder.setLatestStamp()
        if (defMap !== null) {
            builtDefMaps[crate] = defMap
        }
    }

    private fun onCrateFinished(crate: Crate) {
        if (completableFuture.isCompletedExceptionally) return

        crate.reverseDependencies.forEach { onDependencyCrateFinished(it) }
        if (remainingNumberCrates.decrementAndGet() == 0) {
            completableFuture.complete(Unit)
        }
    }

    private fun onDependencyCrateFinished(crate: Crate) {
        val count = remainingDependenciesCounts[crate] ?: return
        if (count.decrementAndGet() == 0) {
            buildDefMapAsync(crate)
        }
    }

    private fun printTimeStatistics(wallTime: Long) {
        check(tasksTimes.size == crates.size)
        val totalTime = tasksTimes.values.sum()
        val top5crates = tasksTimes.entries
            .sortedByDescending { (_, time) -> time }
            .take(5)
            .joinToString { (crate, time) -> "$crate ${time}ms" }
        val multithread = pool !is SingleThreadExecutor
        if (multithread) {
            println("wallTime: $wallTime, totalTime: $totalTime, " +
                "parallelism coefficient: ${"%.2f".format((totalTime.toDouble() / wallTime))}.    " +
                "Top 5 crates: $top5crates")
        } else {
            println("wallTime: $wallTime.    Top 5 crates: $top5crates")
        }
    }
}

fun <T> CompletableFuture<T>.getWithRethrow(): T {
    try {
        return get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

// todo remove
val timesBuildDefMaps: MutableList<Long> = mutableListOf()

fun <T> tryRunReadActionUnderIndicator(indicator: ProgressIndicator, action: () -> T): T {
    try {
        return ApplicationUtil.tryRunReadAction(Computable {
            executeUnderProgress(indicator, action)
        })
    } catch (e: ApplicationUtil.CannotRunReadActionException) {
        throw ProcessCanceledException()
    }
}