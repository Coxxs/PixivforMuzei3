/*
 *     This file is part of PixivforMuzei3.
 *
 *     PixivforMuzei3 is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program  is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.antony.muzei.pixiv.provider

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.*
import com.antony.muzei.pixiv.AppDatabase
import com.antony.muzei.pixiv.BuildConfig
import com.antony.muzei.pixiv.PixivMuzeiSupervisor.getAccessToken
import com.antony.muzei.pixiv.PixivMuzeiSupervisor.post
import com.antony.muzei.pixiv.PixivProviderConst
import com.antony.muzei.pixiv.R
import com.antony.muzei.pixiv.provider.PixivArtProviderDefines.PIXIV_ARTWORK_URL
import com.antony.muzei.pixiv.provider.exceptions.AccessTokenAcquisitionException
import com.antony.muzei.pixiv.provider.exceptions.CorruptFileException
import com.antony.muzei.pixiv.provider.exceptions.FilterMatchNotFoundException
import com.antony.muzei.pixiv.provider.exceptions.LoopFilterMatchNotFoundException
import com.antony.muzei.pixiv.provider.network.AuthJsonServerResponse
import com.antony.muzei.pixiv.provider.network.ImageDownloadServerResponse
import com.antony.muzei.pixiv.provider.network.RankingJsonServerResponse
import com.antony.muzei.pixiv.provider.network.RestClient
import com.antony.muzei.pixiv.provider.network.moshi.AuthArtwork
import com.antony.muzei.pixiv.provider.network.moshi.Contents
import com.antony.muzei.pixiv.provider.network.moshi.Illusts
import com.antony.muzei.pixiv.provider.network.moshi.RankingArtwork
import com.antony.muzei.pixiv.util.HostManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract.getProviderClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Call
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class PixivArtWorker(
        context: Context,
        params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val LOG_TAG = "ANTONY_WORKER"
        private const val WORKER_TAG = "ANTONY"
        private val IMAGE_EXTENSIONS = arrayOf(".png", ".jpg")
        private var clearArtwork = false

        fun enqueueLoad(clear: Boolean, context: Context?) {
            if (clear) {
                clearArtwork = true
            }
            context?.also {
                Constraints.Builder().apply {
                    setRequiredNetworkType(NetworkType.CONNECTED)
                }.let { builder ->
                    OneTimeWorkRequest.Builder(PixivArtWorker::class.java)
                            .setConstraints(builder.build())
                            .addTag(WORKER_TAG)
                            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                            .build()
                }.also { request ->
                    WorkManager.getInstance(it).enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.KEEP, request)
                }
            }
            // Must be a uniqueWork
            // If not Muzei will queue MANY at once on initial load
            // This is good for saturating a network link and for fast picture downloads
            // However, race conditions develop if work required is authenticated
            // unique work ensures that only one Artwork is being processed at once
        }
    }

    enum class FileType {
        OTHER, JPEG, PNG
    }

    private fun writeToFileIllusts(illusts: Illusts) {
        val jsonAdapter: JsonAdapter<Illusts> = Moshi.Builder().build().adapter(Illusts::class.java)

        val json = jsonAdapter.toJson(illusts)
        val file = File(applicationContext.externalCacheDir, "illusts.txt")

        try {
            // response is the data written to file
            PrintWriter(file).use { out -> out.println(json) }
        } catch (e: Exception) {
            // handle the exception
        }
    }

    private fun writeToFileRanking(contents: Contents) {
        val jsonAdapter: JsonAdapter<Contents> = Moshi.Builder().build().adapter(Contents::class.java)

        val json = jsonAdapter.toJson(contents)
        val file = File(applicationContext.externalCacheDir, "contents.txt")

        try {
            // response is the data written to file
            PrintWriter(file).use { out -> out.println(json) }
        } catch (e: Exception) {
            // handle the exception
        }
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
    @Throws(IOException::class)
    private fun getRemoteFileExtension(url: String): ResponseBody? {
        Log.i(LOG_TAG, "Getting remote file extensions")
        // This function is given a thumbnail URL like this
        //  https://tc-pximg01.techorus-cdn.com/c/240x480/img-master/img/2020/02/19/00/00/39/79583564_p0_master1200.jpg

        val transformUrl = "https://i.pximg.net/img-original" + url.substring(url.indexOf("/img/")).replace("_master1200", "")
        // At this point we have a url like this:
        //  https://i.pximg.net/img-original/img/2020/02/19/00/00/39/79583564_p0.jpg

        val transformUrlNoExtension = transformUrl.substring(0, transformUrl.length - 4)
        // Last transformation to remove the file extension
        //  https://i.pximg.net/img-original/img/2020/02/19/00/00/39/79583564_p0

        val bypassActive = PreferenceManager.getDefaultSharedPreferences(applicationContext).getBoolean("pref_enableNetworkBypass", false)
        for (extension in IMAGE_EXTENSIONS) {
            val urlToTest = transformUrlNoExtension + extension
            val service = RestClient.getRetrofitImageInstance(bypassActive).create(ImageDownloadServerResponse::class.java)
            val responseBodyResponse = service.downloadImage(urlToTest).execute()
            val response = responseBodyResponse.raw()
            if (response.isSuccessful) {
                Log.i(LOG_TAG, "Gotten remote file extensions")
                return responseBodyResponse.body()
            }
        }
        Log.e(LOG_TAG, "Failed to get remote file extensions")
        // TODO don't throw a null, throw an exception
        return null
    }

    /*
        PixivforMuzei3 often downloads an incomplete image, i.e. the lower section of images is not
        downloaded, the file header is intact but file closer is not present.
        This function converts the image to a byte representation, then checks the last few bytes
        in the image for a valid file closer.
        If image is incomplete, throws CorruptFileException
        Returns:
            PNG
            JPG
            CorruptFileException
    */
    @Throws(IOException::class, CorruptFileException::class)
    private fun getLocalFileExtension(image: File): FileType {
        Log.d(LOG_TAG, "getting file type")
        val randomAccessFile = RandomAccessFile(image, "r")
        val byteArray = ByteArray(10)
        randomAccessFile.read(byteArray, 0, 2)
        var fileType: FileType = FileType.OTHER

        // ByteArray used instead of read()
        //  read() increments the file-pointer offset, causing successive reads to read different bytes
        if (byteArray[0] == 0x89.toByte() && byteArray[1] == 0x50.toByte()) {
            randomAccessFile.seek(image.length() - 8)
            if (randomAccessFile.readShort() == 0x4945.toShort() && randomAccessFile.readShort() == 0x4E44.toShort()) {
                Log.d(LOG_TAG, "PNG")
                fileType = FileType.PNG
            } else {
                randomAccessFile.close()
                throw CorruptFileException("Corrupt PNG")
            }
        } else if (byteArray[0] == 0xFF.toByte() && byteArray[1] == 0xD8.toByte()) {
            randomAccessFile.seek(image.length() - 2)
            if (randomAccessFile.readShort() == 0xFFD9.toShort()) {
                Log.d(LOG_TAG, "JPG")
                fileType = FileType.JPEG
            } else {
                randomAccessFile.close()
                throw CorruptFileException("Corrupt JPG")
            }
        }
        randomAccessFile.close()
        return fileType
    }

    /*
        First downloads the file to ExternalFilesDir, always with a png file extension
        Checks if the file is incomplete; if incomplete deletes it and passes a CorruptFileException
        up the chain
        Otherwise returns a Uri to the File to the caller
        If option is checked, also makes a copy into external storage
        The external storage copy is not used for backing any database
        The external storage copy also has correct file extensions
     */
    @Throws(IOException::class, CorruptFileException::class)
    private fun downloadFile(responseBody: ResponseBody?,
                             filename: String): Uri {
        Log.i(LOG_TAG, "Downloading file")
        val context = applicationContext
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // If the user has desired to store artworks into external storage
        if (sharedPrefs.getBoolean("pref_storeInExtStorage", false) &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            var allowedToStoreIntoExternal = false
            // TODO stop the hardcode
            val fileExtension = FileType.PNG
            var fosExternal: OutputStream? = null
            var imageUriExternal: Uri? = Uri.EMPTY

            // Android 10 introduced Scoped Storage, aimed at making storage security stronger
            // Results in more hoops to go through to write files
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentResolver = context.contentResolver
                val contentValues = ContentValues()

                // Check if existing copy of file exists
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "title = ?"
                //String selection = {MediaStore.Images.Media.DISPLAY_NAME + " = ? AND ", MediaStore.Images.Media.RELATIVE_PATH + " = ?"};
                val selectionArgs = arrayOf(filename)
                val cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null)
                if (cursor!!.count == 0) {
                    contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixivForMuzei3")
                    if (fileExtension == FileType.PNG) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    } else if (fileExtension == FileType.JPEG) {
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    }

                    // Phone external storage is always "external_primary"
                    // If user has selected artwork to be stored on SD Card external storage, then we fetch a list of
                    // all mounted storages, and then select teh one which isn't "external_primary"
                    // I had assumed that external_primary was always in position 0, but a user report indicated
                    // that external;_primary was in position 1 for them
                    var volumeName = ""
                    if (sharedPrefs.getString("pref_selectWhichExtStorage", "phone").equals("phone")) {
                        volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY
                    } else {
                        val stringSet = MediaStore.getExternalVolumeNames(applicationContext)
                        for (s: String in stringSet) {
                            if (s != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                                volumeName = s
                            }
                        }
                    }

                    // Gives us a URI to save the image to
                    imageUriExternal = contentResolver.insert(MediaStore.Images.Media.getContentUri(volumeName), contentValues)!!
                    fosExternal = contentResolver.openOutputStream(imageUriExternal)
                    allowedToStoreIntoExternal = true
                }
                cursor.close()

                // TODO somehow get a file handle when storing artworks into external storage on Android 10
                // only available android 10+
//                if (sharedPrefs.getBoolean("pref_autoCrop", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                    cropBlankSpaceFromImage(imageJpg)
//                }
            }
            // Android 9 or lower
            else {
                val directoryString = "/storage/emulated/0/Pictures/PixivForMuzei3/"
                val directory = File(directoryString)
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                // If the image has already been downloaded, do not redownload
                val imagePng = File(directoryString, "$filename.png")
                val imageJpg = File(directoryString, "$filename.jpg")
                if (!imageJpg.exists() && !imagePng.exists()) {
                    if (fileExtension == FileType.PNG) {
                        fosExternal = FileOutputStream(imagePng)
                        imageUriExternal = Uri.fromFile(imagePng)
                        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(imagePng)))
                    } else if (fileExtension == FileType.JPEG) {
                        fosExternal = FileOutputStream(imageJpg)
                        imageUriExternal = Uri.fromFile(imageJpg)
                        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(imageJpg)))
                    }
                    allowedToStoreIntoExternal = true
                }
            }
            // Now download into external storage
            if (allowedToStoreIntoExternal) {
                val fis = responseBody!!.byteStream()
                val buffer = ByteArray(1024 * 1024 * 10)
                var lengthInternal: Int
                while (fis.read(buffer).also { lengthInternal = it } > 0) {
                    fosExternal!!.write(buffer, 0, lengthInternal)
                }
                fosExternal!!.close()
                fis.close()
                return imageUriExternal!!
            }
        }

        // If user has not checked the option to "Store into external storage"
        val imageInternal = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "$filename.png")
        val fosInternal = FileOutputStream(imageInternal)
        val inputStreamNetwork = responseBody!!.byteStream()
        val bufferTemp = ByteArray(1024 * 1024 * 10)
        var readTemp: Int
        while (inputStreamNetwork.read(bufferTemp).also { readTemp = it } != -1) {
            fosInternal.write(bufferTemp, 0, readTemp)
        }
        inputStreamNetwork.close()
        fosInternal.close()
        responseBody.close()

        // only available android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sharedPrefs.getBoolean("pref_autoCrop", false)) {
            cropBlankSpaceFromImage(imageInternal)
        }

        return Uri.fromFile(imageInternal)


        // TODO make this an enum
        //val fileExtension = getLocalFileExtension(imageInternal)
    }

    // stolen from https://stackoverflow.com/a/12645803
    // ideal scenario would be to send the image url or whole file to some mini API service
    // which would send back either signal if it's viable for cropping
    // or whole cropped image, so we are not wasting phone battery
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cropBlankSpaceFromImage(file: File) {
        Log.d(LOG_TAG, "Starting cropping")
        val cropStartTime = System.currentTimeMillis()
        val sourceImage = BitmapFactory.decodeFile(file.path)
        val baseColor: Int = sourceImage.getColor(0, 0).toArgb()

        val width = sourceImage.width
        val height = sourceImage.height

        var isCroppable = false
        var topY = Int.MAX_VALUE
        var topX = Int.MAX_VALUE
        var bottomY = -1
        var bottomX = -1
        for (y in 0 until height step 3) {
            for (x in 0 until width step 3) {
                if (isColorWithinTolerance(baseColor, sourceImage.getColor(x, y).toArgb())) {
                    isCroppable = true
                    if (x < topX) topX = x
                    if (y < topY) topY = y
                    if (x > bottomX) bottomX = x
                    if (y > bottomY) bottomY = y
                }
            }
        }

        // sometimes it's off by 1 pixel, so these are not worth processing
        if (!isCroppable || (topX == 0 && topY == 0 && (width == (bottomX + 1)) && (height == (bottomY + 1)))) {
            return
        }

        // @NonNull Bitmap source, int x, int y, int width, int height
        val croppedImage = Bitmap.createBitmap(sourceImage, topX, topY, bottomX - topX + 1, bottomY - topY + 1)

        val output = FileOutputStream(file)
        croppedImage.compress(Bitmap.CompressFormat.PNG, 90, output); // not bothering with JPEG as pixiv sends back only PNGs
        output.close()


        Log.d(LOG_TAG, "Cropping completed in " + (System.currentTimeMillis() - cropStartTime) + " milliseconds")
    }

    private fun isColorWithinTolerance(a: Int, b: Int): Boolean {
        val aAlpha = (a and -0x1000000 ushr 24) // Alpha level
        val aRed = (a and 0x00FF0000 ushr 16) // Red level
        val aGreen = (a and 0x0000FF00 ushr 8) // Green level
        val aBlue = (a and 0x000000FF) // Blue level
        val bAlpha = (b and -0x1000000 ushr 24) // Alpha level
        val bRed = (b and 0x00FF0000 ushr 16) // Red level
        val bGreen = (b and 0x0000FF00 ushr 8) // Green level
        val bBlue = (b and 0x000000FF) // Blue level
        val distance = sqrt((aAlpha - bAlpha) * (aAlpha - bAlpha) + (aRed - bRed) * (aRed - bRed) + (aGreen - bGreen) * (aGreen - bGreen) + ((aBlue - bBlue) * (aBlue - bBlue)).toDouble())

        // 510.0 is the maximum distance between two colors
        // (0,0,0,0 -> 255,255,255,255)
        val percentAway = distance / 510.0

        // tolerance 0.1 means that 2 pixel color values can be from each other up to 10% away
        // to be considered okay for cropping
        return percentAway > 0.10
    }

    // TODO is this even necessary anymore
    private fun isArtworkNull(artwork: Artwork?): Boolean =
            artwork.also {
                it ?: Log.e(LOG_TAG, "Null artwork returned, retrying at later time")
            } == null

    /*
        Provided an artowrk ID (token), traverses the PixivArtProvider ContentProvider to sees
        if there is already a duplicate artwork with the same ID (token)
     */
    private fun isDuplicateArtwork(token: Int): Boolean {
        var duplicateFound = false
        val projection = arrayOf("_id")
        val selection = "token = ?"
        val selectionArgs = arrayOf(token.toString())
        val conResUri = getProviderClient(applicationContext, PixivArtProvider::class.java).contentUri
        val cursor: Cursor? = applicationContext.contentResolver.query(conResUri, projection, selection, selectionArgs, null)

        if (cursor != null) {
            duplicateFound = cursor.count > 0
        }
        cursor?.close()
        return duplicateFound
    }

    private fun hasDesiredPixelSize(
            width: Int,
            height: Int,
            minimumWidth: Int,
            minimumHeight: Int,
            aspectRatioSetting: Int
    ): Boolean =
            when (aspectRatioSetting) {
                0 -> height >= (minimumHeight * 10) && width >= (minimumWidth * 10)
                1 -> height >= (minimumHeight * 10)
                2 -> width >= (minimumWidth * 10)
                else -> true
            }

    /*
        0   Any aspect ratio
        1   Landscape
        2   Portrait
     */
    private fun isDesiredAspectRatio(
            width: Int,
            height: Int,
            aspectRatioSetting: Int
    ): Boolean =
            when (aspectRatioSetting) {
                0 -> true
                1 -> height >= width
                2 -> height <= width
                else -> true
            }

    // Scalar must match with scalar in SettingsActivity
    private fun isEnoughViews(
            artworkViewCount: Int,
            minimumDesiredViews: Int
    ): Boolean = artworkViewCount >= minimumDesiredViews * 500

    private fun isImageTooLarge(sizeBytes: Long, limitBytes: Long): Boolean = sizeBytes > limitBytes

    private fun isBeenDeleted(artworkId: Int): Boolean =
            (AppDatabase.getInstance(applicationContext)?.deletedArtworkIdDao()?.isRowIsExist(artworkId)!!)


    /*
        Receives a Contents object, which contains a representnation of a set of artworks
        Passes it off to filterArtworkRanking(), which returns one ranking artwork
        Builds an Artwork object off returned ranking artwork
     */
    @Throws(IOException::class, CorruptFileException::class, FilterMatchNotFoundException::class)
    private fun getArtworkRanking(contents: Contents?): Artwork {
        Log.i(LOG_TAG, "getArtworkRanking(): Entering")
        var attribution = ""
        when (contents!!.mode) {
            "daily" -> attribution = applicationContext.getString(R.string.attr_daily)
            "weekly" -> attribution = applicationContext.getString(R.string.attr_weekly)
            "monthly" -> attribution = applicationContext.getString(R.string.attr_monthly)
            "rookie" -> attribution = applicationContext.getString(R.string.attr_rookie)
            "original" -> attribution = applicationContext.getString(R.string.attr_original)
            "male" -> attribution = applicationContext.getString(R.string.attr_male)
            "female" -> attribution = applicationContext.getString(R.string.attr_female)
            else -> ""
        }
        val attributionDate = contents.date
        val attTrans = attributionDate.substring(0, 4) + "/" + attributionDate.substring(4, 6) + "/" + attributionDate.substring(6, 8) + " "

        //writeToFile(overallJson, "rankingLog.txt");
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Filter variables, to pass to filterArtworkRanking()
        val showManga = sharedPrefs.getBoolean("pref_showManga", false)
        val defaultRankingSelect: MutableSet<String> = HashSet()
        defaultRankingSelect.add("0")
        val rankingFilterSelect = sharedPrefs.getStringSet("pref_rankingFilterSelect", defaultRankingSelect)
        val aspectRatioSettings = sharedPrefs.getString("pref_aspectRatioSelect", "0")!!.toInt()
        val minimumViews = sharedPrefs.getInt("prefSlider_minViews", 0)

        // Filtering
        val rankingArtwork = filterArtworkRanking(contents.artworks.toMutableList(),
                showManga,
                rankingFilterSelect,
                aspectRatioSettings,
                minimumViews,
                sharedPrefs.getInt("prefSlider_minimumWidth", 0),
                sharedPrefs.getInt("prefSlider_minimumHeight", 0)
        )

        // Variables to submit to Muzei
        val token = rankingArtwork!!.illust_id.toString()
        attribution = attTrans + attribution
        attribution += rankingArtwork.rank

        // Actually downloading the selected artwork
        val remoteFileExtension = getRemoteFileExtension(rankingArtwork.url)
        val localUri = downloadFile(remoteFileExtension, token)

//        val fileSizeLimit = sharedPrefs.getInt("prefSlider_maxFileSize", 0)
//        // 1024 scalar to convert MB to byte
//        if (fileSizeLimit != 0 && isImageTooLarge(remoteFileExtension!!.contentLength(), fileSizeLimit * 1048576.toLong()))
//        {
//            Log.v("SIZE", "too chonk")
//            //throw new ImageTooLargeException("");
//            // grab a new image, somehwo loop back
//        }
//        else
//        {
//            Log.v("SIZE", "good size")
//        }
        remoteFileExtension!!.close()
        Log.i(LOG_TAG, "getArtworkRanking(): Exited")
        return Artwork.Builder()
                .title(rankingArtwork.title)
                .byline(rankingArtwork.user_name)
                .attribution(attribution)
                .persistentUri(localUri)
                .token(token)
                .webUri(Uri.parse(PIXIV_ARTWORK_URL + token))
                .build()
    }

    /*
        Filters through a MutableList containing RankingArtwork's.
        Picks one image based on the user's various filtering settings.

            NSFW filtering is performed by checking the value of the "sexual" JSON string
            Manga filtering is performed by checking the value of the "illust_type" JSON string
    */
    @Throws(FilterMatchNotFoundException::class)
    private fun filterArtworkRanking(rankingArtworkList: MutableList<RankingArtwork>,
                                     showManga: Boolean,
                                     selectedFilterLevelSet: Set<String>?,
                                     aspectRatioSetting: Int,
                                     minimumViews: Int,
                                     minimumWidth: Int,
                                     minimumHeight: Int
    ): RankingArtwork? {
        Log.i(LOG_TAG, "filterRanking(): Entering")

        rankingArtworkList.shuffle()
        for (randomArtwork in rankingArtworkList) {
            try {
                return filterRankingArtworkSingle(randomArtwork,
                        showManga,
                        selectedFilterLevelSet,
                        aspectRatioSetting,
                        minimumViews,
                        minimumWidth,
                        minimumHeight)
            } catch (e: LoopFilterMatchNotFoundException) {
                Log.e(LOG_TAG, e.message!!)
                continue
            }
        }
        throw FilterMatchNotFoundException("All artworks in traversed, fetching a new Contents")
    }

    private fun filterRankingArtworkSingle(rankingArtwork: RankingArtwork,
                                           showManga: Boolean,
                                           selectedFilterLevelSet: Set<String>?,
                                           aspectRatioSetting: Int,
                                           minimumViews: Int,
                                           minimumWidth: Int,
                                           minimumHeight: Int
    ): RankingArtwork {
        if (isDuplicateArtwork(rankingArtwork.illust_id)) {
            throw LoopFilterMatchNotFoundException("Duplicate ID: " + rankingArtwork.illust_id)
        }
        if (!isEnoughViews(rankingArtwork.view_count, minimumViews)) {
            throw LoopFilterMatchNotFoundException("Not enough views " + rankingArtwork.illust_id)
        }
        if (!showManga && rankingArtwork.illust_type == 1) {
            throw LoopFilterMatchNotFoundException("Manga not desired " + rankingArtwork.illust_id)
        }
        if (!isDesiredAspectRatio(rankingArtwork.width,
                        rankingArtwork.height, aspectRatioSetting)) {
            throw LoopFilterMatchNotFoundException("Rejecting aspect ratio " + rankingArtwork.illust_id)
        }
        if (!hasDesiredPixelSize(rankingArtwork.width, rankingArtwork.height, minimumWidth, minimumHeight, aspectRatioSetting)) {
            throw LoopFilterMatchNotFoundException("Image below desired pixel size " + rankingArtwork.illust_id)
        }
        if (isBeenDeleted(rankingArtwork.illust_id)) {
            throw LoopFilterMatchNotFoundException("Previously deleted " + rankingArtwork.illust_id)
        }

        if (selectedFilterLevelSet!!.size == 2) {
            return rankingArtwork
        } else {
            for (s in selectedFilterLevelSet) {
                if (s.toInt() == rankingArtwork.illust_content_type.sexual) {
                    Log.v(LOG_TAG, "matching NSFW " + rankingArtwork.illust_id)
                    return rankingArtwork
                }
            }
            throw LoopFilterMatchNotFoundException("not matching NSFW " + rankingArtwork.illust_id)
        }
    }

    /*
        Receives a list of auth artworks
        Passes it off to filterArtworkRanking(), which returns one ranking artwork
        Builds an Artwork object off returned ranking artwork
     */
    @Throws(FilterMatchNotFoundException::class, IOException::class, CorruptFileException::class)
    private fun getArtworkAuth(authArtworkList: List<AuthArtwork>, isRecommended: Boolean): Artwork {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Filter variables to pass to filterArtworkAuth()
        val aspectRatioSettings = sharedPrefs.getString("pref_aspectRatioSelect", "0")!!.toInt()
        val showManga = sharedPrefs.getBoolean("pref_showManga", false)
        // null default case allowed
        // App *MUST* be first opened in order to change the update mode and log in
        // Opening the app populates the shared preference with a default entry
        // As opposed to ranking, where there can be an empty shared preference
        val selectedFilterLevel = sharedPrefs.getStringSet("pref_authFilterSelect", null)
        val minimumViews = sharedPrefs.getInt("prefSlider_minViews", 0)

        // Filtering
        val selectedArtwork = filterArtworkAuth(
                authArtworkList.toMutableList(),
                showManga,
                selectedFilterLevel,
                aspectRatioSettings,
                minimumViews,
                isRecommended,
                sharedPrefs.getInt("prefSlider_minimumWidth", 0),
                sharedPrefs.getInt("prefSlider_minimumHeight", 0)
        )

        // Variables for submitting to Muzei
        val imageUrl: String = if (selectedArtwork!!.meta_pages.size == 0) {
            Log.d(LOG_TAG, "Picture is a single image")
            selectedArtwork
                    .meta_single_page
                    .original_image_url
        } else {
            Log.d(LOG_TAG, "Picture is part of an album")
            selectedArtwork
                    .meta_pages[0]
                    .image_urls
                    .original
        }
        val token = selectedArtwork.id.toString()
        val bypassActive = sharedPrefs.getBoolean("pref_enableNetworkBypass", false)

        // Actually downloading the file
        var imageDataResponse: ResponseBody?
        val useCeuiLiSAWay = true
        if (useCeuiLiSAWay) {
            /**
             * new code, replace url host to ip address and download
             * this way runs well on my phone
             *
             * there is something in logcat:
             *
             * Submitting 3 artworks
             * Work completed
             *
             * is this normal?
             */
            val finalUrl = HostManager.get().replaceUrl(imageUrl)
            Log.d("finalUrl", finalUrl)
            val request: Request = Request.Builder().url(finalUrl).get().build()
            val imageHttpClient = OkHttpClient.Builder()
                    .addInterceptor(Interceptor { chain: Interceptor.Chain ->
                        val original = chain.request()
                        val request = original.newBuilder()
                                .header("Referer", PixivProviderConst.PIXIV_HOST_URL)
                                .build()
                        chain.proceed(request)
                    })
                    .build()
            val call = imageHttpClient.newCall(request)
            imageDataResponse = call.execute().body
        } else {
            // its your original code
            val service = RestClient.getRetrofitImageInstance(bypassActive).create(ImageDownloadServerResponse::class.java)
            val call = service.downloadImage(imageUrl)
            imageDataResponse = call.execute().body()
        }

        val localUri = downloadFile(imageDataResponse, token)
//        val fileSizeLimitMegabytes = sharedPrefs.getInt("prefSlider_maxFileSize", 0)
//        // 1024 scalar to convert from MB to bytes
//        if (fileSizeLimitMegabytes != 0 && isImageTooLarge(imageDataResponse!!.contentLength(), fileSizeLimitMegabytes * 1048576.toLong()))
//        {
//            Log.v("SIZE", "too chonk")
//        }
//        else
//        {
//            Log.v("SIZE", "good size")
//        }

        imageDataResponse!!.close()
        Log.i(LOG_TAG, "getArtworkAuth(): Exited")
        return Artwork.Builder()
                .title(selectedArtwork.title)
                .byline(selectedArtwork.user.name)
                .persistentUri(localUri)
                .token(token)
                .webUri(Uri.parse(PIXIV_ARTWORK_URL + token))
                .build()
    }

    /*
        Called by getArtworkAuth to return details about an artwork that complies with
        filtering restrictions set by the user

        For NSFW filtering the two relevant JSON strings are "sanity_level" and "x_restrict"
            sanity_level
                2 -> Completely SFW
                4 -> Moderately ecchi e.g. beach bikinis, slight upskirts
                6 -> Very ecchi e.g. more explicit and suggestive themes
             x_restrict
                1 -> R18 e.g. nudity and penetration

            In this code x_restrict is treated as a level 8 sanity_level

        For manga filtering, the value of the "type" string is checked for either "manga" or "illust"
     */
    @Throws(FilterMatchNotFoundException::class)
    private fun filterArtworkAuth(authArtworkList: MutableList<AuthArtwork>,
                                  showManga: Boolean,
                                  selectedFilterLevelSet: Set<String>?,
                                  aspectRatioSetting: Int,
                                  minimumViews: Int,
                                  isRecommended: Boolean,
                                  minimumWidth: Int,
                                  minimumHeight: Int
    ): AuthArtwork? {
        Log.i(LOG_TAG, "filterArtworkAuth(): Entering")

        authArtworkList.shuffle()
        for (randomArtwork in authArtworkList) {
            try {
                return filterArtworkAuthSingle(randomArtwork,
                        showManga,
                        selectedFilterLevelSet,
                        aspectRatioSetting,
                        minimumViews,
                        isRecommended,
                        minimumWidth,
                        minimumHeight)
            } catch (e: LoopFilterMatchNotFoundException) {
                Log.e(LOG_TAG, e.message!!)
            }
        }
        throw FilterMatchNotFoundException("All artworks traversed, fetching a new Illusts")
    }

    private fun filterArtworkAuthSingle(authArtwork: AuthArtwork,
                                        showManga: Boolean,
                                        selectedFilterLevelSet: Set<String>?,
                                        aspectRatioSetting: Int,
                                        minimumViews: Int,
                                        isRecommended: Boolean,
                                        minimumWidth: Int,
                                        minimumHeight: Int
    ): AuthArtwork? {
// Check if duplicate before any other check to not waste time
        if (isDuplicateArtwork(authArtwork.id)) {
            throw LoopFilterMatchNotFoundException("Duplicate ID: " + authArtwork.id)
        }

        // If user does not want manga to display
        if (!showManga && authArtwork.type == "manga") {
            throw LoopFilterMatchNotFoundException("Manga not desired " + authArtwork.id)
        }

        // Filter artwork based on chosen aspect ratio
        if (!isDesiredAspectRatio(authArtwork.width,
                        authArtwork.height, aspectRatioSetting)) {
            throw LoopFilterMatchNotFoundException("Rejecting aspect ratio " + authArtwork.id)
        }

        if (!hasDesiredPixelSize(authArtwork.width, authArtwork.height, minimumWidth, minimumHeight, aspectRatioSetting)) {
            throw LoopFilterMatchNotFoundException("Image below desired pixel size " + authArtwork.id)
        }

        if (!isEnoughViews(authArtwork.total_view, minimumViews)) {
            throw LoopFilterMatchNotFoundException("Not enough views " + authArtwork.id)
        }

        if (isBeenDeleted(authArtwork.id)) {
            throw LoopFilterMatchNotFoundException("Previously deleted " + authArtwork.id)
        }

        // All artworks in recommended are SFW, we can skip this check
        if (isRecommended || selectedFilterLevelSet!!.size == 4) {
            return authArtwork
        } else {
            // See if there is a match between chosen artwork's sanity level and those desired
            for (s in selectedFilterLevelSet) {
                if (s == authArtwork.sanity_Level.toString()) {
                    Log.d(LOG_TAG, "sanity_level found is " + authArtwork.sanity_Level)
                    Log.i(LOG_TAG, "Found artwork " + authArtwork.id)
                    return authArtwork
                } else if (s == "8" && authArtwork.x_restrict == 1) {
                    Log.d(LOG_TAG, "x_restrict found " + authArtwork.id)
                    return authArtwork
                }
            }
            Log.d(LOG_TAG, "sanity_level found is " + authArtwork.sanity_Level)
            throw LoopFilterMatchNotFoundException("NSFW not matching " + authArtwork.id)
        }
    }

    /*
        Main meat of the app
        Obtains an up to date access token if required
        Obtains objects that represent each update mode, and will continue to obtain objects until
        enough artworks have satisfied
        Returns a list of Artwork's for submission into Muzei
     */
    @get:Throws(IOException::class, CorruptFileException::class)
    private val artwork: ArrayList<Artwork>?
        get() {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            var updateMode = sharedPrefs.getString("pref_updateMode", "daily")

            // These modes require an access token, so we check for and acquire one first
            if (PixivArtProviderDefines.AUTH_MODES.contains(updateMode)) {
                try {
                    getAccessToken()
                } catch (ex: AccessTokenAcquisitionException) {
                    when (sharedPrefs.getString("pref_authFailAction", "changeDaily")) {
                        "changeDaily" -> {
                            Log.d(LOG_TAG, "Auth failed, changing mode to daily")
                            sharedPrefs.edit().putString("pref_updateMode", "daily").apply()
                            updateMode = "daily"
                            post(Runnable {
                                Toast.makeText(
                                        applicationContext,
                                        R.string.toast_authFailedSwitch,
                                        Toast.LENGTH_SHORT
                                ).show()
                            })
                        }
                        "doNotChange_downDaily" -> {
                            Log.d(LOG_TAG, "Auth failed, downloading a single daily")
                            updateMode = "daily"
                            post(Runnable {
                                Toast.makeText(applicationContext, R.string.toast_authFailedDown, Toast.LENGTH_SHORT).show()
                            })
                        }
                        "doNotChange_doNotDown" -> {
                            Log.d(LOG_TAG, "Auth failed, retrying with no changes")
                            post(Runnable {
                                Toast.makeText(
                                        applicationContext,
                                        R.string.toast_authFailedRetry,
                                        Toast.LENGTH_SHORT
                                ).show()
                            })
                            return null
                        }
                    }
                }
            }

            val artworkArrayList = ArrayList<Artwork>()
            var artwork: Artwork
            val bypassActive = sharedPrefs.getBoolean("pref_enableNetworkBypass", false)
            if (PixivArtProviderDefines.AUTH_MODES.contains(updateMode)) {
                val service = RestClient.getRetrofitAuthInstance(bypassActive).create(AuthJsonServerResponse::class.java)
                var call: Call<Illusts?>
                call = when (updateMode) {
                    "follow" -> service.followJson
                    "bookmark" -> service.getBookmarkJson(sharedPrefs.getString("userId", ""))
                    "recommended" -> service.recommendedJson
                    "artist" -> service.getArtistJson(sharedPrefs.getString("pref_artistId", ""))
                    "tag_search" -> service.getTagSearchJson(sharedPrefs.getString("pref_tagSearch", ""))
                    else -> throw IllegalStateException("Unexpected value: $updateMode")
                }
                var illusts = call.execute().body()

                if (BuildConfig.DEBUG && illusts != null) {
                    writeToFileIllusts(illusts)
                }
                var authArtworkList = illusts!!.artworks
                for (i in 0 until sharedPrefs.getInt("prefSlider_numToDownload", 2)) {
                    try {
                        artwork = getArtworkAuth(authArtworkList, updateMode == "recommended")
                        if (isArtworkNull(artwork)) {
                            throw CorruptFileException("")
                        }
                        artworkArrayList.add(artwork)
                    } catch (e: FilterMatchNotFoundException) {
                        e.printStackTrace()
                        // I'm not sure how many times we can keep getting the nextUrl
                        // TODO implement a limit on the number of nextUrls
                        call = service.getNextUrl(illusts!!.nextUrl)
                        illusts = call.execute().body()
                        authArtworkList = illusts!!.artworks
                    }
                }
            } else {
                val service = RestClient.getRetrofitRankingInstance(bypassActive).create(RankingJsonServerResponse::class.java)
                var call = service.getRankingJson(updateMode)
                var contents = call.execute().body()
                if (BuildConfig.DEBUG && contents != null) {
                    writeToFileRanking(contents)
                }
                var pageNumber = 1
                var date = contents!!.date
                var prevDate = contents.prev_date
                for (i in 0 until sharedPrefs.getInt("prefSlider_numToDownload", 2)) {
                    try {
                        artwork = getArtworkRanking(contents)
                        if (isArtworkNull(artwork)) {
                            throw CorruptFileException("")
                        }
                        artworkArrayList.add(artwork)
                    } catch (e: FilterMatchNotFoundException) {
                        e.printStackTrace()
                        // If enough artworks are not found in the 50 from the first page of the rankings,
                        // keep looking through the next pages or days
                        // We can continue to look through the 450 rankings for that day
                        // There is a tenth page actually, but the next page number integer becomes a boolean
                        // GSON can't handle this and throws a fit.
                        // Thus I've limited my app to parsing only the top 450 rankings
                        if (pageNumber != 9) {
                            pageNumber++
                            call = service.getRankingJson(updateMode, pageNumber, date)
                            contents = call.execute().body()
                        } else {
                            // If we for some reason cannot find enough artwork to satisfy the filter
                            // from the top 450, then we can look at the previous day's ranking
                            pageNumber = 1
                            call = service.getRankingJson(updateMode, pageNumber, prevDate)
                            contents = call.execute().body()
                            date = contents!!.date
                            prevDate = contents.prev_date
                        }
                    }
                }
            }
            Log.i(LOG_TAG, "Submitting " + sharedPrefs.getInt("prefSlider_numToDownload", 2) +
                    " artworks")
            return artworkArrayList
        }

    override fun doWork(): Result {
        Log.d(LOG_TAG, "Starting work")

        val client = getProviderClient(applicationContext, PixivArtProvider::class.java)
        val artworkArrayList: ArrayList<Artwork>? = try {
            artwork
        } catch (e: IOException) {
            e.printStackTrace()
            return Result.retry()
        } catch (e: CorruptFileException) {
            e.printStackTrace()
            return Result.retry()
        }
        if (clearArtwork) {
            clearArtwork = false
            client.setArtwork(artworkArrayList!!)
        } else {
            client.addArtwork(artworkArrayList!!)
        }
        Log.d(LOG_TAG, "Work completed")
        return Result.success()
    }
}
