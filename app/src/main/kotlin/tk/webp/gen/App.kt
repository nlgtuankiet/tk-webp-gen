/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package tk.webp.gen

import averageBy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import okhttp3.internal.threadFactory
import okhttp3.logging.HttpLoggingInterceptor
import org.nield.kotlinstatistics.averageBy
import org.nield.kotlinstatistics.medianBy
import org.nield.kotlinstatistics.percentileBy
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class App {
  val greeting: String
    get() {
      return "Hello World!"
    }
}

var debug = false

val client: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .apply {
      val dispatcher = Dispatcher()
      dispatcher.maxRequests = Int.MAX_VALUE
      dispatcher.maxRequestsPerHost = Int.MAX_VALUE
      dispatcher.executorService
      dispatcher(dispatcher)
      connectTimeout(1, TimeUnit.HOURS)
      readTimeout(1, TimeUnit.HOURS)
      writeTimeout(1, TimeUnit.HOURS)
      if (debug) {
        val logger = HttpLoggingInterceptor { message -> println("OkHttp: $message") }
        logger.level = HttpLoggingInterceptor.Level.HEADERS
        addNetworkInterceptor(logger)
      }
    }
    .build()

}

data class UrlInfo(
  val index: Int,
  val total: Int,
  val url: String
)

val writeOutputContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
val ioContext = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
  SynchronousQueue(), threadFactory("url worker", false)).asCoroutineDispatcher()


var errorCount = 0
val tikiCache = mutableMapOf<String?, Int>()
var analyze = false

fun main(args: Array<String>) = runBlocking {
  val urlsPath = args[args.indexOf("-urls") + 1]
  val errorPath = args[args.indexOf("-error") + 1]
  val done = args[args.indexOf("-done") + 1]
  val worker = args[args.indexOf("-worker") + 1].toInt()
  analyze = args.contains("-analyze")
  println("done: $done")
  println("errorPath: $errorPath")
  debug = args.any { it == "-debug" }
  val urlFile = File(urlsPath)

  val doneFile = File(done)
  if (!doneFile.exists()) {
    doneFile.createNewFile()
  }
  require(doneFile.isFile)

  val errorFile = File(errorPath)
  if (!errorFile.exists()) {
    errorFile.createNewFile()
  }
  require(errorFile.isFile)

  println("Reading url files...")
  val urls = urlFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }
    .drop(1)
    .asSequence()
    .filter { !it.endsWith(".JPG") }
    .filter { !it.endsWith(".PNG") }
    .filter { !it.endsWith(".JPEG") }

    .toMutableSet()

  val initInput = urls.size
  require(urls.isNotEmpty())

  println("Reading done files...")
  val doneUrls = doneFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }

  println("Reading error files...")
  val errorUrls = errorFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }

  println("Remove done urls...")
  doneUrls.forEach {
    urls.remove(it)
  }
  println("Remove error urls...")
  errorUrls.forEach {
    urls.remove(it)
  }
  val urlsChannel = Channel<UrlInfo>(UNLIMITED)
  urls.forEachIndexed { index, s ->
    urlsChannel.offer(UrlInfo(index, urls.size, s))
  }
  urlsChannel.close()

  println("Input $initInput urls, ${doneUrls.size} is done, ${errorUrls.size} errors, ${urls.size} to go...")

  repeat(worker) {
    launch(ioContext) {
      for (info in urlsChannel) {
        val result = runCatching {
          processUrl(info)
        }
        withContext(writeOutputContext) {
          when {
            result.isSuccess -> {
              doneFile.appendText("${info.url}\n")
            }
            result.isFailure -> {
              println("error ${info.url}")
              errorCount++
              result.exceptionOrNull()?.printStackTrace()
              errorFile.appendText("${info.url}\n")
            }
          }
        }
      }
    }
  }


}

private val profiles = listOf(200, 280, 350, 400, 540, 750, 1080)
private val sizePaths = profiles.map { listOf("cache", "h$it") }
private val history = LinkedList<Long>()
private val maxHistory = 15000

data class AnalyticEntry(val cache: String, val ttfb: Long)

private val analyticEntries = mutableListOf<AnalyticEntry>()

suspend fun processUrl(urlInfo: UrlInfo) = coroutineScope {
  launch(ioContext) {
    val percent = 100 * (urlInfo.index + 1.0) / urlInfo.total
    val (min, max, total) = synchronized(history) {
      val current = System.currentTimeMillis()
      history.add(current)
      while (history.size > maxHistory) {
        history.remove()
      }
      Triple(history.peek() ?: current, current, history.size)
    }
    val duration = (max - min)
    val speedInfo = if (duration > 0) {
      val itemPerSecond = 1.0 * total / (duration / 1000.0)
      val itemLeft = urlInfo.total - urlInfo.index + 1
      val secondsLeft = (itemLeft) / itemPerSecond
      val hoursLeft = (secondsLeft / 60) / 60
      "%.1f ips %.1fh left".format(itemPerSecond * 7, hoursLeft)
    } else {
      null
    }

    val line = buildString {
      append("p ${urlInfo.index + 1}/${urlInfo.total} e: $errorCount ")
      synchronized(tikiCache) {
        tikiCache.toList().sortedBy { it.second }.forEach {
          append("${it.first}:${it.second} ")
        }
      }
      append("$speedInfo")
      append(" | %.2f%%".format(percent))
      if (analyze) {
        val analyticEntriesCopy = synchronized(analyticEntries) {
          analyticEntries.toList()
        }
        if (analyticEntriesCopy.isNotEmpty()) {
          appendLine()

          append("count: ")
          analyticEntriesCopy.groupBy { it.cache }.toSortedMap().forEach { (t, u) ->
            append("`$t`-`${u.size}` ")
          }

          listOf(99, 95, 80, 50, 5).forEach {
            appendLine()
            val tpxx = analyticEntriesCopy.percentileBy(it.toDouble(), { it.cache }, { it.ttfb }).toSortedMap()
            append("tp$it: ")
            tpxx.forEach { (t, u) -> append("`$t`-`${u.toInt()}ms` ") }
          }
          appendLine()

          val avg = analyticEntriesCopy.asSequence()
            .averageBy(keySelector = { it.cache }, longSelector = {it.ttfb}).toSortedMap()
          append("avg: ")
          avg.forEach { (t, u) -> append("`$t`-`${u.toInt()}` ") }
          appendLine()

          val median = analyticEntriesCopy.asSequence()
            .medianBy(keySelector = { it.cache },valueSelector = {it.ttfb}).toSortedMap()
          append("median: ")
          median.forEach { (t, u) -> append("`$t`-`${u.toInt()}` ") }
          appendLine()
        }
      }
    }
    println(line)
  }
  val url = urlInfo.url.toHttpUrl()
  val baseUrl = HttpUrl.Builder()
    .scheme(url.scheme)
    .host(url.host)
    .build()
  val lastPathIndex = url.pathSegments.lastIndex
  val urls = sizePaths.map { size ->
    baseUrl.newBuilder()
      .apply {
        size.forEach { addPathSegment(it) }
        url.pathSegments.forEachIndexed { index, s ->
          if (index == lastPathIndex) {
            addPathSegment("$s.webp")
          } else {
            addPathSegment(s)
          }
        }
      }
      .build()
  }
  urls.forEach {
    var hitCount = 0
    var tryCount = 0
    var isFirst = true
    while (isFirst) {
      tryCount++
      if (tryCount > 100) {
        error("try count > 100")
      }
      val request = Request.Builder().url(it).build()
      val call = client.newCall(request)
      val response = call.execute()
      val code = response.code
      val tikiCacheString = response.headers["tiki-cache"]?.trim()
      if (isFirst && analyze) {
        val ttfb = response.receivedResponseAtMillis - response.sentRequestAtMillis
        synchronized(analyticEntries) {
          analyticEntries.add(AnalyticEntry(tikiCacheString ?: "", ttfb))
        }
      }
      if (isFirst) {
        isFirst = false
      }
      val errorMessage = "error $code ${response.message} $url"
      synchronized(tikiCache) {
        val oldValue = tikiCache[tikiCacheString] ?: 0
        val newValue = oldValue + 1
        tikiCache[tikiCacheString] = newValue
      }
      response.closeQuietly()
      if (code != 200) {
        println(errorMessage)
        error("code $code $url")
      }
      if (code == 200 && tikiCacheString == "HIT") {
        hitCount++
      } else {
        hitCount = 0
      }
    }

  }
}
