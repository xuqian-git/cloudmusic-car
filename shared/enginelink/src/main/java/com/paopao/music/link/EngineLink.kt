package com.paopao.music.link

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer

/**
 * 功能包的界面（桌面主进程）和引擎（桌面 :music 子进程）之间的连接。
 *
 * 桌面 HOST_API 3 只提供一条「通用传输口」：界面侧拿到 `call(方法名, Bundle) -> Bundle` 和事件订阅，
 * 引擎侧拿到事件发射器。方法名、参数、事件由功能包自己约定，都在这里收口：
 *
 * - **镜像状态**：引擎把 StateFlow 登记成 [EngineSide.mirror]，值一变就推给界面；界面侧同名的
 *   MutableStateFlow 登记成 [UiSide.mirror]，收到就赋值。界面代码照旧读原来那个单例的 StateFlow。
 * - **命令**：引擎 [EngineSide.command] 登记处理函数；界面 [UiSide.call] / [UiSide.fire] 调用。
 * - **大块数据**（几千首的队列、整首歌词）超过 [INLINE_LIMIT] 走同一应用私有目录里的临时文件，
 *   避开 Binder 1MB 的事务上限。
 *
 * 同一个单例在两个进程里各有一份：引擎里是真身，界面里是镜像。代码里用 [isUi] 分流。
 */
object EngineLink {
    /** 当前进程是功能包界面（主进程，经 HOST_API 3 加载）。false 表示引擎或老桌面的单进程模式。 */
    @Volatile var isUi: Boolean = false
        private set

    /** 当前进程是引擎（桌面 :music 子进程）。和 [isUi] 都为 false 时是老桌面的单进程模式或独立 App。 */
    @Volatile var isEngine: Boolean = false
        private set

    private const val EVENT_CONNECTED = "host.connected"
    private const val EVENT_DISCONNECTED = "host.disconnected"
    private const val CMD_SYNC = "link.sync"
    private const val KEY_ERROR = "link.error"
    private const val KEY_ERROR_CODE = "link.errorCode"
    private const val KEY_VALUE = "v"
    private const val KEY_FILE = "link.file"
    private const val INLINE_LIMIT = 200 * 1024

    private lateinit var spoolDir: File

    // ---------------- 界面侧 ----------------

    object UiSide {
        private var call: BiFunction<String, Bundle?, Bundle?>? = null
        private val mirrors = ConcurrentHashMap<String, (String) -> Unit>()
        private val handlers = ConcurrentHashMap<String, (Bundle) -> Unit>()
        private val _connected = MutableStateFlow(false)
        /** 引擎连上了没有：断线（:music 崩溃重启）期间界面显示「正在连接」。 */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Suppress("UNCHECKED_CAST")
        fun attach(context: Context, callFn: Any, subscribeFn: Any) {
            spoolDir = File(context.cacheDir, "music-link").apply { mkdirs() }
            isUi = true
            call = callFn as BiFunction<String, Bundle?, Bundle?>
            (subscribeFn as Consumer<BiConsumer<String, Bundle>?>).accept(BiConsumer { name, data -> onEvent(name, data) })
        }

        fun detach(subscribeFn: Any?) {
            @Suppress("UNCHECKED_CAST")
            (subscribeFn as? Consumer<BiConsumer<String, Bundle>?>)?.accept(null)
        }

        /** 登记镜像：引擎同名 [EngineSide.mirror] 每次变化都会把编码后的值送到 [apply]（主线程）。 */
        fun <T> mirror(name: String, flow: MutableStateFlow<T>, decode: (String) -> T) {
            mirrors[name] = { text -> flow.value = decode(text) }
        }

        /** 引擎的一次性事件（toast 等）。 */
        fun on(name: String, handler: (Bundle) -> Unit) {
            handlers[name] = handler
        }

        private fun onEvent(name: String, data: Bundle) {
            when (name) {
                EVENT_CONNECTED -> {
                    _connected.value = true
                    // 刚连上（含 :music 重启后）：让引擎把所有镜像状态整份推一遍。
                    fire(CMD_SYNC)
                }
                EVENT_DISCONNECTED -> _connected.value = false
                else -> {
                    mirrors[name]?.let { apply -> readText(data)?.let(apply); return }
                    handlers[name]?.invoke(data)
                }
            }
        }

        /** 同步调用引擎。不要在主线程用：主线程没连上时会直接拿到 [EngineUnavailable]。 */
        fun call(method: String, args: Bundle = Bundle()): Bundle {
            val fn = call ?: throw EngineUnavailable()
            // 引擎进程死了（DeadObject）或还没装好引擎：对界面来说都是「暂时连不上」。
            val reply = try {
                fn.apply(method, args)
            } catch (e: Exception) {
                throw EngineUnavailable(e)
            } ?: throw EngineUnavailable()
            reply.getString(KEY_ERROR)?.let { throw EngineCallException(reply.getInt(KEY_ERROR_CODE, -1), it) }
            return reply
        }

        suspend fun callAsync(method: String, args: Bundle = Bundle()): Bundle =
            withContext(Dispatchers.IO) { call(method, args) }

        /** 不关心结果的命令（播放控制等）：按发出顺序在一条后台线程上逐个送达，失败静默。 */
        fun fire(method: String, args: Bundle = Bundle()) {
            scope.launch(serial) { runCatching { call(method, args) } }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val serial = Dispatchers.IO.limitedParallelism(1)
    }

    // ---------------- 引擎侧 ----------------

    object EngineSide {
        private var emit: BiConsumer<String, Bundle>? = null
        private val commands = ConcurrentHashMap<String, (Bundle) -> Bundle?>()
        private val syncers = ConcurrentHashMap<String, () -> Unit>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Suppress("UNCHECKED_CAST")
        fun attach(context: Context, events: Any) {
            spoolDir = File(context.cacheDir, "music-link").apply { mkdirs() }
            // 上一个引擎进程没来得及读走的临时文件：引擎是新起的，旧的都没人要了。
            spoolDir.listFiles().orEmpty().forEach(File::delete)
            isEngine = true
            emit = events as BiConsumer<String, Bundle>
            command(CMD_SYNC) { syncers.values.forEach { it() }; null }
        }

        /**
         * 登记镜像：[flow] 当前值和之后每次变化都编码推给界面。[encode] 在主线程跑，
         * 大列表（队列）请保证编码够快——几千首几十毫秒以内。
         */
        fun <T> mirror(name: String, flow: StateFlow<T>, encode: (T) -> String) {
            val push = { send(name, Bundle().also { putText(it, encode(flow.value)) }) }
            syncers[name] = { scope.launch { push() } }
            scope.launch { flow.drop(1).collect { value -> send(name, Bundle().also { putText(it, encode(value)) }) } }
        }

        /** 处理函数跑在 Binder 线程：碰播放器的要自己切到主线程（见 [onMain]）。返回 null 表示没有结果。 */
        fun command(name: String, handler: (Bundle) -> Bundle?) {
            commands[name] = handler
        }

        fun send(name: String, data: Bundle = Bundle()) {
            runCatching { emit?.accept(name, data) }
        }

        /** 宿主 [com.carhome.musicplugin.MusicEngineService] 反射调用的入口。异常一律折成结果，不抛过 Binder。 */
        fun dispatch(method: String, args: Bundle): Bundle {
            val handler = commands[method] ?: return error(-1, "未知调用：$method")
            return runCatching { handler(args) ?: Bundle() }.getOrElse { failure ->
                val code = (failure as? CodedException)?.errorCode ?: -1
                error(code, failure.message ?: failure.javaClass.simpleName)
            }
        }

        private fun error(code: Int, message: String) = Bundle().apply {
            putString(KEY_ERROR, message)
            putInt(KEY_ERROR_CODE, code)
        }

        /** 在引擎主线程上同步执行（ExoPlayer 只能在创建它的线程上用），最多等 5 秒。 */
        fun <T> onMain(block: () -> T): T {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: Result<T>? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result = runCatching(block)
                latch.countDown()
            }
            check(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "播放器忙，请稍后再试" }
            return result!!.getOrThrow()
        }
    }

    // ---------------- 编码 ----------------

    /** 字符串值：小的直接放 Bundle，大的写进临时文件只传路径。 */
    fun putText(bundle: Bundle, text: String) {
        if (text.length * 2 < INLINE_LIMIT) {
            bundle.putString(KEY_VALUE, text)
            return
        }
        val file = File(spoolDir, "${SystemClock.elapsedRealtimeNanos()}-${Thread.currentThread().id}.json")
        file.writeText(text)
        bundle.putString(KEY_FILE, file.absolutePath)
    }

    fun readText(bundle: Bundle): String? {
        bundle.getString(KEY_VALUE)?.let { return it }
        val path = bundle.getString(KEY_FILE) ?: return null
        val file = File(path)
        if (file.parentFile != spoolDir) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }

    fun text(text: String): Bundle = Bundle().also { putText(it, text) }

    /** 引擎抛出带业务码的异常时实现它，码会原样带回界面。 */
    interface CodedException {
        val errorCode: Int
    }

    class EngineUnavailable(cause: Throwable? = null) : IllegalStateException("音乐引擎正在启动，请稍候", cause)
    class EngineCallException(val code: Int, message: String) : RuntimeException(message)
}
