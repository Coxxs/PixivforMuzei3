package com.antony.muzei.pixiv.provider

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import androidx.work.*
import com.antony.muzei.pixiv.PixivMuzeiSupervisor
import com.antony.muzei.pixiv.PixivMuzeiSupervisor.getAccessToken
import com.antony.muzei.pixiv.PixivProviderConst
import com.antony.muzei.pixiv.PixivProviderConst.AUTH_MODES
import com.antony.muzei.pixiv.R
import com.antony.muzei.pixiv.provider.exceptions.AccessTokenAcquisitionException
import com.antony.muzei.pixiv.provider.exceptions.FilterMatchNotFoundException
import com.antony.muzei.pixiv.provider.network.moshi.AuthArtwork
import com.antony.muzei.pixiv.provider.network.moshi.Contents
import com.antony.muzei.pixiv.provider.network.moshi.RankingArtwork
import com.antony.muzei.pixiv.util.HostManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract.getProviderClient
import okhttp3.*
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class PixivArtWorker2(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {
    companion object {
        const val LOG_TAG = "ANTONY_WORKER"
        private const val WORKER_TAG = "ANTONY"
        private val IMAGE_EXTENSIONS = arrayOf(".jpg", ".png")

        // Variable that tracks if the artwork cache needs to be cleared
        private var clearArtwork = false

        internal fun enqueueLoad(clearArtworkRequested: Boolean, context: Context) {
            if (clearArtworkRequested) {
                clearArtwork = true
            }

            Constraints.Builder().apply {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }.let { builder ->
                OneTimeWorkRequest.Builder(PixivArtWorker2::class.java)
                    .setConstraints(builder.build())
                    .addTag(WORKER_TAG)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                    .build()
            }.let { request ->
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.KEEP, request)
            }
            // The Work must be a UniqueWork
            // If not unique work, multiple works can be submitted at the processed at the same time
            // This can lead to race conditions if a new access token is needed
            // Additionally, we definitely do not want to spam the API
        }
    }

    private fun downloadImage(
        responseBody: ResponseBody?,
        filename: String,
        storeInExtStorage: Boolean
    ): Uri {
        val fileType = responseBody?.contentType()

        return if (!storeInExtStorage) {
            downloadImageInternal(responseBody, filename, fileType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadImageExternalApi29(responseBody, filename, fileType)
        } else {
            downloadImageExternalApi28(responseBody, filename, fileType)
        }
    }

    // Function to download images to external storage
    // External storage in this case refers to /storage/emulated/0/Pictures/PixivForMuzei3
    // Option is also there to store onto an SD card if present
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadImageExternalApi29(
        responseBody: ResponseBody?,
        filename: String,
        fileType: MediaType?,
    ): Uri {
        val contentResolver = applicationContext.contentResolver

        /* Checking if existing copy of images exists*/
        // Specifying that I want only _ID and DISPLAY_NAME columns returned
        // Specifying that I only want rows that have a DISPLAY_NAME matching the filename passed
        // Executing the query
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(filename),
            null
        )
        // If an existing image is found, return that URI, and do not download anything else
        // If cursor is null, then we proceed with the download
        if (cursor != null && cursor.count != 0) {
            Log.v(LOG_TAG, "downloadImageAPI10: Duplicate found")
            val imageUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)).toLong()
            )
            cursor.close()
            return imageUri
        }
        cursor?.close()


        // Inserting the filename, relative path within the /Pictures folder, and MIME type into the content provider
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixivForMuzei3")
            put(MediaStore.MediaColumns.MIME_TYPE, fileType.toString())
        }

        // Default option is VOLUME_EXTERNAL_PRIMARY
        // If user has selected the option to store onto SD card, first we check if there is more than one storage mounted
        // Handles the case when SD card is selected but is then unmounted
        // Then, iterate through the array of mounted storages until we find one that is not VOLUME_EXTERNAL_PRIMARY
        // The manual iterating is required as I received a user report where VOLUME_EXTERNAL_PRIMARY was not the first entry
        var volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY
        if (!PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getString("pref_selectWhichExtStorage", "phone").equals("phone")
        ) {
            MediaStore.getExternalVolumeNames(applicationContext).takeIf { it.size > 1 }
                ?.let { volumeNames ->
                    for (volume in volumeNames) {
                        if (volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                            volumeName = volume
                        }
                    }
                }
        }

        // Actually doing the download
        //Gives us a URI to save the image to
        val imageUri = contentResolver.insert(
            MediaStore.Images.Media.getContentUri(volumeName),
            contentValues
        )!!
        // TODO handle this null

        // The other method using BufferedSink doesn't work all we have is a URI to sink into
        val fis = responseBody!!.byteStream()
        val fosExternal: OutputStream? = contentResolver.openOutputStream(imageUri)
        val buffer = ByteArray(1024 * 1024 * 10)
        var lengthInternal: Int
        while (fis.read(buffer).also { lengthInternal = it } > 0) {
            fosExternal!!.write(buffer, 0, lengthInternal)
        }
        fosExternal!!.close()
        fis.close()

        return imageUri
    }

    // Function to download images to "external storage"
    // External storage is described at the path below
    private fun downloadImageExternalApi28(
        responseBody: ResponseBody?,
        filename: String,
        fileType: MediaType?,
    ): Uri {
        // Checks if directory exists. If nonexistent, then create it
        val directory = File("/storage/emulated/0/Pictures/PixivForMuzei3/")
            .takeIf { !it.exists() }?.also { it.mkdirs() }

        File(directory, "$filename.${fileType!!.subtype}").also { image ->
            if (!image.exists()) {
                // Broadcast the addition of a new media file
                // Solves problem where the images were not showing up in their gallery up until a scan was triggered
                applicationContext.sendBroadcast(
                    Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(image))
                )
            } else {
                // If the image has already been downloaded, do not redownload
                return Uri.fromFile(image)
            }
        }.let { image ->
            image.sink().buffer().let {
                it.writeAll(responseBody!!.source())
                responseBody.close()
                it.close()
            }
            return Uri.fromFile(image)
        }
    }


    // Function used to download images to internal storage
    // Internal storage in this case is /storage/emulated/0/Android/data/com.antony.muzei.pixiv/files
    private fun downloadImageInternal(
        responseBody: ResponseBody?,
        filename: String,
        fileType: MediaType?,
    ): Uri {
        File(
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "$filename.${fileType!!.subtype}"
        ).also {
            if (it.exists()) {
                return Uri.fromFile(it)
            }
        }.let {
            val sink: BufferedSink = it.sink().buffer()
            sink.writeAll(responseBody!!.source())
            responseBody.close()
            sink.close()
            return Uri.fromFile(it)
        }
    }

    // Each call to this function returns a single Ranking artwork
    private fun getArtworkRanking(contents: Contents): Artwork {
        Log.i(LOG_TAG, "getArtworkRanking(): Entering")
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Filtering
        val rankingArtwork = filterArtworkRanking(
            contents.artworks.toMutableList(),
            sharedPrefs.getBoolean("pref_showManga", false),
            sharedPrefs.getStringSet("pref_rankingFilterSelect", setOf("0")) ?: setOf("0"),
            sharedPrefs.getString("pref_aspectRatioSelect", "0")?.toInt() ?: 0,
            sharedPrefs.getInt("prefSlider_minViews", 0),
            sharedPrefs.getInt("prefSlider_minimumWidth", 0),
            sharedPrefs.getInt("prefSlider_minimumHeight", 0)
        )

        val attribution = contents.date.run {
            substring(0, 4) + "/" + substring(4, 6) + "/" + substring(6, 8) + " "
        }.plus(
            when (contents.mode) {
                "daily" -> applicationContext.getString(R.string.attr_daily)
                "weekly" -> applicationContext.getString(R.string.attr_weekly)
                "monthly" -> applicationContext.getString(R.string.attr_monthly)
                "rookie" -> applicationContext.getString(R.string.attr_rookie)
                "original" -> applicationContext.getString(R.string.attr_original)
                "male" -> applicationContext.getString(R.string.attr_male)
                "female" -> applicationContext.getString(R.string.attr_female)
                else -> ""
            }
        ).plus(rankingArtwork.rank)

        val token = rankingArtwork.illust_id.toString()

        // this may be null
        // if it's null, then we have experienced an issue getting remote file extensions
        val remoteFileExtension = getRemoteFileExtension(rankingArtwork.url)
        val localUri = downloadImage(
            remoteFileExtension,
            token,
            sharedPrefs.getBoolean("pref_storeInExtStorage", false)
        )
        // TODO file size limit filter
        // TODO handle this null
        remoteFileExtension!!.close()

        Log.i(LOG_TAG, "getArtworkRanking(): Exited")
        return Artwork.Builder()
            .title(rankingArtwork.title)
            .byline(rankingArtwork.user_name)
            .attribution(attribution)
            .persistentUri(localUri)
            .token(token)
            .webUri(Uri.parse(PixivProviderConst.PIXIV_ARTWORK_URL + token))
            .build()
    }

    private fun filterArtworkRanking(
        toMutableList: MutableList<RankingArtwork>,
        boolean: Boolean,
        set: Set<String>,
        i: Int,
        int: Int,
        int1: Int,
        int2: Int
    ): RankingArtwork {
        TODO()
    }

    /*
    Ranking images are only provided with a URL to a low resolution thumbnail
    We want the high resolution image, so we need to do some work first

    Secondly, the thumbnail is always a .jpg
    For the high resolution image we require a correct file extension
    This method tests all file extensions (PNG or JPG) until a good response is received
        i.e. a response that is not a 400 class error
    Returns a ResponseBody which contains the picture to download
*/
    private fun getRemoteFileExtension(thumbnailUrl: String): ResponseBody? {
        /* Deliberately not turned into scope function to optimize readability */

        // This function is given a thumbnail URL like this
        //  https://tc-pximg01.techorus-cdn.com/c/240x480/img-master/img/2020/02/19/00/00/39/79583564_p0_master1200.jpg
        val transformUrl =
            "https://i.pximg.net/img-original" + thumbnailUrl.substring(thumbnailUrl.indexOf("/img/"))
                .replace("_master1200", "")
        // At this point we have a url like this:
        //  https://i.pximg.net/img-original/img/2020/02/19/00/00/39/79583564_p0.jpg
        val transformUrlNoExtension = transformUrl.substring(0, transformUrl.length - 4)
        // Last transformation removes the trailing file extensions
        //  https://i.pximg.net/img-original/img/2020/02/19/00/00/39/79583564_p0

        for (extension in IMAGE_EXTENSIONS) {
            val urlToTest = transformUrlNoExtension + extension

            val finalUrl = HostManager.get().replaceUrl(urlToTest)
            val remoteFileExtenstionRequest: Request = Request.Builder()
                .url(finalUrl)
                .get()
                .build()
            val imageHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain: Interceptor.Chain ->
                    val original = chain.request()
                    val request = original.newBuilder()
                        .header("Referer", PixivProviderConst.PIXIV_API_HOST_URL)
                        .build()
                    chain.proceed(request)
                })
                .build()

            imageHttpClient.newCall(remoteFileExtenstionRequest).execute().let {
                if (it.isSuccessful) {
                    Log.i(LOG_TAG, "Gotten remote file extensions")
                    return it.body
                }
            }
        }
        // TODO don't throw a null, throw an exception
        return null
    }

    private fun getArtworkAuth(
        artworkList: List<AuthArtwork>,
        isRecommended: Boolean
    ): Artwork {
        TODO()
    }

    // Returns a list of Artworks to Muzei
    //
    private fun getArtworks(): MutableList<Artwork>? {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        var updateMode = sharedPrefs.getString("pref_updateMode", "daily")

        // Gets an up to date access token if required
        if (AUTH_MODES.contains(updateMode)) {
            try {
                getAccessToken()
            } catch (e: AccessTokenAcquisitionException) {
                updateMode = authHandleAuthFailure(sharedPrefs) ?: return null
            }
        }

        // App has functionality to temporarily or permanently change the update mode if authentication fails
        // i.e. update mode can change between the previous if block and this if block
        // Thus two identical if statements are required
        if (AUTH_MODES.contains(updateMode)) {
            // Determines if any extra information is needed, and passes it along
            val data = when (updateMode) {
                "bookmark" -> sharedPrefs.getString("userId", "")
                "artist" -> sharedPrefs.getString("pref_artistId", "")
                "tag_search" -> sharedPrefs.getString("pref_tagSearch", "")
                else -> ""
            }
            // illustsHelper is stateful, stores a copy of Illusts, and can fetch a new one if needed
            val illustsHelper = IllustsHelper(updateMode ?: "recommended", data ?: "")
            var authArtworkList = illustsHelper.getNewIllusts().artworks

            for (i in 0 until sharedPrefs.getInt("prefSlider_numToDownload", 2)) {
                try {
                    getArtworkAuth(authArtworkList, false)
                } catch (e: FilterMatchNotFoundException) {
                    authArtworkList = illustsHelper.getNextIllusts().artworks
                }
            }
        } else {
            // contentsHelper is stateful, stores a copy of Contents, and can fetch a new one if needed
            val contentsHelper = ContentsHelper(updateMode ?: "daily")
            var contents = contentsHelper.getNewContents()

            for (i in 0 until sharedPrefs.getInt("prefSlider_numToDownload", 2)) {
                try {
                    getArtworkRanking(contents)
                } catch (e: FilterMatchNotFoundException) {
                    contents = contentsHelper.getNextContents()
                }
            }
        }
        Log.i(
            LOG_TAG, "Submitting " + sharedPrefs.getInt("prefSlider_numToDownload", 2) +
                    " artworks"
        )
        return mutableListOf<Artwork>()
    }

    private fun authHandleAuthFailure(sharedPrefs: SharedPreferences): String? {
        Log.i(LOG_TAG, "Failed to acquire access token")
        when (sharedPrefs.getString("pref_authFailAction", "changeDaily")) {
            "changeDaily" -> {
                Log.i(LOG_TAG, "Changing mode to daily")
                sharedPrefs.edit().putString("pref_updateMode", "daily").apply()
                PixivMuzeiSupervisor.post(Runnable {
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_authFailedSwitch,
                        Toast.LENGTH_SHORT
                    ).show()
                })
                return "daily"
            }
            "doNotChange_downDaily" -> {
                Log.i(LOG_TAG, "Downloading a single daily")
                PixivMuzeiSupervisor.post(Runnable {
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_authFailedDown,
                        Toast.LENGTH_SHORT
                    ).show()
                })
                return "daily"
            }
            "doNotChange_doNotDown" -> {
                Log.i(LOG_TAG, "Retrying with no changes")
                PixivMuzeiSupervisor.post(Runnable {
                    Toast.makeText(
                        applicationContext,
                        R.string.toast_authFailedRetry,
                        Toast.LENGTH_SHORT
                    ).show()
                })
                return null
            }
        }
        return null
    }

    override fun doWork(): Result {
        val providerClient = getProviderClient(applicationContext, PixivArtProvider::class.java)

        val artworks = getArtworks() ?: return Result.retry()

        if (clearArtwork) {
            clearArtwork = false
            providerClient.setArtwork(artworks)
        } else {
            providerClient.addArtwork(artworks)
        }

        return Result.success()
    }
}
