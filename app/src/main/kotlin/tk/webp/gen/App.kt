/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package tk.webp.gen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
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

fun main(args: Array<String>) = runBlocking {
  val urlsPath = args[args.indexOf("-urls") + 1]
  val errorPath = args[args.indexOf("-error") + 1]
  val done = args[args.indexOf("-done") + 1]
  val worker = args[args.indexOf("-worker") + 1].toInt()
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

  val urls = urlFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }
    .drop(1)
    .let {
      if (debug) {
        (it.take(10))
      } else {
        it
      }
    }
    .toMutableList()
  val initInput = urls.size
  require(urls.isNotEmpty())

  val doneUrls = doneFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }

  val errorUrls = errorFile.readLines().map { it.trim() }
    .filter { it.isNotEmpty() }

  doneUrls.forEach {
    urls.remove(it)
  }
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

suspend fun processUrl(urlInfo: UrlInfo) {
  val percent = 100 * (urlInfo.index + 1.0) / urlInfo.total
  println("process ${urlInfo.index + 1}/${urlInfo.total} %.2f%%".format(percent))
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
    val request = Request.Builder().url(it).build()
    val call = client.newCall(request)
    val response = call.execute()
    response.closeQuietly()
    call.cancel()
    if (response.code != 200) {
      println("error ${response.code} $url")
      error("code ${response.code} $url")
    }
  }
}
