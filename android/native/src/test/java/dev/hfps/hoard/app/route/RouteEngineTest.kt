package dev.hfps.hoard.app.route

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The tests of `src/features/route/engine.test.ts`, one for one, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteEngineTest {

    private val guid = "6f1c2b9e-3a5d-4c7e-9b21-0d8e4f7a1c35"
    private val foreign = "ffffffff-0000-4000-8000-000000000001"

    data class Host(
        /** The probe answers after this many ms (virtual); default 1. */
        val delay: Long? = null,
        val reachable: Boolean = true,
        val reason: ErrorCode = ErrorCode.UNREACHABLE,
        val latencyMs: Long? = null,
        /** Server GUID behind this host. */
        val guid: String? = null,
        /** Connect fails with this code (after the GUID check). */
        val connectError: ErrorCode? = null,
    )

    inner class FakeSmb(val hosts: MutableMap<String, Host>) : SmbForEngine {
        var sessions = 0
        val live = HashSet<String>()
        val probes = ArrayList<Pair<String, Long>>()
        val connects = ArrayList<String?>()
        val disconnects = ArrayList<String>()

        override suspend fun probe(host: String, port: Int, timeoutMs: Long): ProbeResult {
            probes += host to timeoutMs
            val behavior = hosts[host] ?: Host(reachable = false)
            delay(behavior.delay ?: 1)
            return if (!behavior.reachable) {
                ProbeResult(false, reason = behavior.reason)
            } else {
                ProbeResult(true, latencyMs = behavior.latencyMs ?: behavior.delay ?: 1)
            }
        }

        override suspend fun connect(machineId: String, host: String, port: Int, expectedServerGuid: String?, timeoutMs: Long): ConnectResult {
            connects += expectedServerGuid
            val behavior = hosts[host] ?: Host()
            delay(1)
            val serverGuid = behavior.guid ?: guid
            if (expectedServerGuid != null && expectedServerGuid != serverGuid) {
                throw HoardSmbException(ErrorCode.GUID_MISMATCH, "mismatch", data = mapOf("serverGuid" to serverGuid))
            }
            behavior.connectError?.let { throw HoardSmbException(it, it.name) }
            val id = "s${++sessions}"
            live += id
            return ConnectResult(id, serverGuid, "SERVER", "SMB_3_1_1")
        }

        override fun disconnect(sessionId: String) {
            disconnects += sessionId
            live -= sessionId
        }
    }

    private fun machine(hosts: List<String>, serverGuid: String? = guid, id: String = "m1") =
        EngineMachine(id, serverGuid, hosts.map { EngineAddress(it) })

    private fun TestScope.engine(smb: FakeSmb, saved: MutableList<Pair<String, String>> = ArrayList(), probeTimeout: Long = 1500) =
        RouteEngine(backgroundScope, smb, saveGuid = { id, g -> saved += id to g }, probeTimeoutMs = { probeTimeout }, now = { testScheduler.currentTime })

    // --- check -----------------------------------------------------------------------------------------------

    @Test
    fun connectsToTheFirstReachableAddressInPriorityOrder() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host(delay = 4, latencyMs = 4), "100.101.12.34" to Host(delay = 70)))
        val engine = engine(smb)
        val state = engine.check(machine(listOf("192.168.1.155", "100.101.12.34"))).await()
        assertThat(state.status).isEqualTo(RouteStatus.Online)
        assertThat(state.route).isEqualTo(ActiveRoute("192.168.1.155", 445, AddressKind.Local, 4))
        assertThat(engine.state("m1")).isEqualTo(state)
    }

    @Test
    fun doesNotWaitForASlowDeadAddressOnceAHigherPriorityOneAnswers() = runTest {
        val smb = FakeSmb(
            mutableMapOf(
                "192.168.1.155" to Host(delay = 5),
                "100.88.7.5" to Host(delay = 1500, reachable = false, reason = ErrorCode.TIMEOUT),
            ),
        )
        val engine = engine(smb)
        engine.check(machine(listOf("192.168.1.155", "100.88.7.5")))
        advanceTimeBy(50)
        assertThat(engine.state("m1").status).isEqualTo(RouteStatus.Online)
    }

    @Test
    fun failsOverWhenTheFirstAddressIsUnreachable() = runTest {
        val smb = FakeSmb(mutableMapOf("10.0.0.40" to Host(delay = 30, reachable = false), "office.example.com" to Host(delay = 160)))
        val state = engine(smb).check(machine(listOf("10.0.0.40", "office.example.com"))).await()
        assertThat(state.route?.host).isEqualTo("office.example.com")
        assertThat(state.route?.kind).isEqualTo(AddressKind.Other)
        assertThat(state.attempts[0].probe).isEqualTo(ProbeResult(false, reason = ErrorCode.UNREACHABLE))
    }

    @Test
    fun treatsAGuidMismatchAsUnreachableAndContinues() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.0.10" to Host(delay = 2, guid = foreign), "100.101.12.34" to Host(delay = 70)))
        val state = engine(smb).check(machine(listOf("192.168.0.10", "100.101.12.34"))).await()
        assertThat(state.status).isEqualTo(RouteStatus.Online)
        assertThat(state.route?.host).isEqualTo("100.101.12.34")
        assertThat(state.attempts[0].connect).isEqualTo("GUID_MISMATCH")
        assertThat(smb.connects).containsExactly(guid, guid).inOrder()
    }

    @Test
    fun reportsGuidMismatchWithTheForeignGuid() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.0.10" to Host(guid = foreign)))
        val state = engine(smb).check(machine(listOf("192.168.0.10"))).await()
        assertThat(state.status).isEqualTo(RouteStatus.Error)
        assertThat(state.errorCode).isEqualTo(ErrorCode.GUID_MISMATCH)
        assertThat(state.foreignGuid).isEqualTo(foreign)
    }

    @Test
    fun pinsTheGuidOnTheFirstConnectAndUsesItAfterwards() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        val saved = ArrayList<Pair<String, String>>()
        val engine = engine(smb, saved)
        val m = machine(listOf("192.168.1.155"), serverGuid = null)
        engine.check(m).await()
        assertThat(saved).containsExactly("m1" to guid)
        assertThat(engine.knownGuid("m1")).isEqualTo(guid)
        // The caller still holds the stale row (no GUID): the pinned one is used anyway.
        engine.check(m, force = true).await()
        assertThat(smb.connects).containsExactly(null, guid).inOrder()
    }

    @Test
    fun stopsOnAuthFailedAndKeepsTheRoute() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host(connectError = ErrorCode.AUTH_FAILED), "100.101.12.34" to Host()))
        val state = engine(smb).check(machine(listOf("192.168.1.155", "100.101.12.34"))).await()
        assertThat(state.status).isEqualTo(RouteStatus.Error)
        assertThat(state.errorCode).isEqualTo(ErrorCode.AUTH_FAILED)
        assertThat(state.route?.host).isEqualTo("192.168.1.155")
        assertThat(smb.connects).hasSize(1)
    }

    @Test
    fun isOfflineTimeoutWhenEveryAddressTimesOutUnreachableOtherwise() = runTest {
        val smb = FakeSmb(
            mutableMapOf(
                "a" to Host(reachable = false, reason = ErrorCode.TIMEOUT),
                "b" to Host(reachable = false, reason = ErrorCode.TIMEOUT),
            ),
        )
        val engine = engine(smb)
        engine.check(machine(listOf("a", "b"))).await().let {
            assertThat(it.status).isEqualTo(RouteStatus.Offline)
            assertThat(it.errorCode).isEqualTo(ErrorCode.TIMEOUT)
        }
        engine.check(machine(listOf("a", "zzz"), id = "m2")).await().let {
            assertThat(it.status).isEqualTo(RouteStatus.Offline)
            assertThat(it.errorCode).isEqualTo(ErrorCode.UNREACHABLE)
        }
        assertThat(engine.check(machine(emptyList(), id = "m3")).await().status).isEqualTo(RouteStatus.Offline)
    }

    @Test
    fun passesTheProbeTimeoutSettingToEveryProbe() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        engine(smb, probeTimeout = 900).check(machine(listOf("192.168.1.155"))).await()
        assertThat(smb.probes.map { it.second }).containsExactly(900L)
    }

    @Test
    fun sharesOneInFlightCheckAndReusesTheSession() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        val engine = engine(smb)
        val m = machine(listOf("192.168.1.155"))
        val a = engine.check(m)
        val b = engine.check(m)
        assertThat(a).isSameInstanceAs(b)
        listOf(a, b).awaitAll()
        engine.check(m).await()
        assertThat(smb.connects).hasSize(1)
    }

    // --- sessions --------------------------------------------------------------------------------------------

    @Test
    fun withSessionReconnectsOnceWhenTheSessionIsGone() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        val engine = engine(smb)
        val m = machine(listOf("192.168.1.155"))
        engine.check(m).await()
        smb.live.clear() // native dropped every session
        var calls = 0
        val result = engine.withSession(m) { sessionId ->
            calls++
            if (sessionId !in smb.live) throw HoardSmbException(ErrorCode.NOT_FOUND, "gone")
            sessionId
        }
        assertThat(result).isEqualTo("s2")
        assertThat(calls).isEqualTo(2)
        assertThat(smb.connects).hasSize(2)
    }

    @Test
    fun withSessionRethrowsASecondNotFound() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        val engine = engine(smb)
        val error = runCatching {
            engine.withSession(machine(listOf("192.168.1.155"))) { throw HoardSmbException(ErrorCode.NOT_FOUND, "gone") }
        }.exceptionOrNull()
        assertThat(errorCodeOf(error!!)).isEqualTo(ErrorCode.NOT_FOUND)
    }

    @Test
    fun sessionRejectsWithTheRoutesErrorCodeWhenOffline() = runTest {
        val engine = engine(FakeSmb(mutableMapOf()))
        val error = runCatching { engine.session(machine(listOf("203.0.113.9"))) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RouteException::class.java)
        assertThat(errorCodeOf(error!!)).isEqualTo(ErrorCode.UNREACHABLE)
    }

    // --- recheckAll ------------------------------------------------------------------------------------------

    @Test
    fun recheckAllDropsSessionsAndRechecksEveryKnownMachineOnceDebounced() = runTest {
        val hosts = mutableMapOf("192.168.1.155" to Host(), "100.101.12.34" to Host(delay = 70))
        val smb = FakeSmb(hosts)
        val engine = engine(smb)
        val m = machine(listOf("192.168.1.155", "100.101.12.34"))
        engine.check(m).await()
        assertThat(engine.state("m1").route?.kind).isEqualTo(AddressKind.Local)

        // Left home: Wi-Fi gone, Tailscale up.
        hosts["192.168.1.155"] = Host(reachable = false)
        engine.recheckAll()
        engine.recheckAll() // resume + network change arrive together
        advanceTimeBy(260)
        runCurrent()
        assertThat(engine.state("m1").status).isEqualTo(RouteStatus.Checking)
        // While checking, the last known route stays visible.
        assertThat(engine.state("m1").route?.host).isEqualTo("192.168.1.155")
        advanceTimeBy(500)
        val state = engine.state("m1")
        assertThat(state.status).isEqualTo(RouteStatus.Online)
        assertThat(state.route?.host).isEqualTo("100.101.12.34")
        assertThat(state.route?.kind).isEqualTo(AddressKind.Tailscale)
        assertThat(smb.disconnects).containsExactly("s1")
        assertThat(smb.connects).hasSize(2)
    }

    @Test
    fun ignoresAStaleCheckThatFinishesAfterANewerOne() = runTest {
        val smb = FakeSmb(mutableMapOf("slow" to Host(delay = 500), "fast" to Host(delay = 5)))
        val engine = engine(smb)
        val first = engine.check(machine(listOf("slow")))
        advanceTimeBy(10)
        val second = engine.check(machine(listOf("fast")), force = true)
        listOf(first, second).awaitAll()
        advanceUntilIdle()
        assertThat(engine.state("m1").route?.host).isEqualTo("fast")
    }

    @Test
    fun forgetClearsStateAndDisconnects() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host()))
        val engine = engine(smb)
        engine.check(machine(listOf("192.168.1.155"))).await()
        engine.forget("m1")
        assertThat(engine.state("m1").status).isEqualTo(RouteStatus.Idle)
        assertThat(smb.disconnects).containsExactly("s1")
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    @Test
    fun orderForTransferPutsTheRouteInUseFirst() {
        val addresses = listOf(EngineAddress("192.168.1.155"), EngineAddress("100.101.12.34"), EngineAddress("cachyos-fps"))
        assertThat(orderForTransfer(addresses, ActiveRoute("100.101.12.34", 445, AddressKind.Tailscale)).map { it.host })
            .containsExactly("100.101.12.34", "192.168.1.155", "cachyos-fps").inOrder()
        assertThat(orderForTransfer(addresses, null)).isEqualTo(addresses)
    }

    @Test
    fun nativeRouteConnectsFirstWhenNoGuidIsPinned() = runTest {
        val smb = FakeSmb(mutableMapOf("192.168.1.155" to Host(delay = 3), "100.101.12.34" to Host(delay = 70)))
        val engine = engine(smb)
        val route = engine.nativeRoute(machine(listOf("100.101.12.34", "192.168.1.155"), serverGuid = null))
        assertThat(route.expectedServerGuid).isEqualTo(guid)
        // 100.101.12.34 has priority and answers: it is the route, so it stays first.
        assertThat(route.addresses.map { it.host }).containsExactly("100.101.12.34", "192.168.1.155").inOrder()
    }

    @Test
    fun pickRouteReturnsNullWhenNothingAnswers() = runTest {
        val outcome = pickRoute(listOf(1, 2)) { async { ProbeResult(false) } }
        assertThat(outcome).isNull()
    }

    @Test
    fun addressKindsAndHostInput() {
        assertThat(AddressKind.detect("100.101.12.34")).isEqualTo(AddressKind.Tailscale)
        assertThat(AddressKind.detect("nas.tail1234.ts.net")).isEqualTo(AddressKind.Tailscale)
        assertThat(AddressKind.detect("fd7a:115c:a1e0::1")).isEqualTo(AddressKind.Tailscale)
        assertThat(AddressKind.detect("192.168.1.2")).isEqualTo(AddressKind.Local)
        assertThat(AddressKind.detect("172.20.0.1")).isEqualTo(AddressKind.Local)
        assertThat(AddressKind.detect("nas.local")).isEqualTo(AddressKind.Local)
        assertThat(AddressKind.detect("[fe80::1]")).isEqualTo(AddressKind.Local)
        assertThat(AddressKind.detect("8.8.8.8")).isEqualTo(AddressKind.Other)
        assertThat(AddressKind.detect("cachyos-fps")).isEqualTo(AddressKind.Other)
        assertThat(parseHostInput("smb://nas.local/share")).isEqualTo(HostInput("nas.local"))
        assertThat(parseHostInput("\\\\192.168.1.20\\Public")).isEqualTo(HostInput("192.168.1.20"))
        assertThat(parseHostInput("192.168.1.155:4450")).isEqualTo(HostInput("192.168.1.155", 4450))
        assertThat(parseHostInput("[fd7a:115c:a1e0::1]:445")).isEqualTo(HostInput("fd7a:115c:a1e0::1", 445))
        assertThat(parseHostInput("   ")).isNull()
        assertThat(parseHostInput("two words")).isNull()
        assertThat(AddressKind.sortByKind(listOf("8.8.8.8", "100.64.0.2", "10.0.0.1")) { it })
            .containsExactly("10.0.0.1", "100.64.0.2", "8.8.8.8").inOrder()
    }
}
