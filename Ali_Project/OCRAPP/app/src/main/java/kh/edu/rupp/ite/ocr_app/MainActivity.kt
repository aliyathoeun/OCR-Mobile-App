package kh.edu.rupp.ite.ocr_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.converter.gson.GsonConverterFactory
import android.os.Environment
import android.os.Build

class MainActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textResult: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            // Convert URI to File
            val inputStream = contentResolver.openInputStream(selectedUri)
            val file = File(cacheDir, "temp_image.jpg")
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Send the file to server
            sendImageToServer(file)
        }
    }

    private val pickImageForDetection = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri ->
            val inputStream = contentResolver.openInputStream(selectedUri)
            val file = File(cacheDir, "temp_image_detect.jpg")
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            sendImageForDetection(file)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        textResult = findViewById(R.id.textResult)
        
        findViewById<MaterialButton>(R.id.buttonCapture).setOnClickListener {
            takePhoto()
        }

        findViewById<MaterialButton>(R.id.buttonUpload).setOnClickListener {
            openGallery()
        }

        findViewById<MaterialButton>(R.id.buttonDetect).setOnClickListener {
            openGalleryForDetection()
        }

        findViewById<MaterialButton>(R.id.buttonDownload).setOnClickListener {
            downloadDocxFile()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasPermission()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        checkStoragePermission()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            outputDirectory,
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    sendImageToServer(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun sendImageToServer(imageFile: File) {
        Log.d(TAG, "Sending image file: ${imageFile.absolutePath}, exists: ${imageFile.exists()}, size: ${imageFile.length()}")

        // Create OkHttpClient with timeout settings
        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        // Create multipart body
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                imageFile.name,
                imageFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        // Build request with all headers
        val request = Request.Builder()
            .url("https://khmerdococr.pythonanywhere.com/ocr")
            .post(body)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .addHeader("Accept-Language", "km-KH,km;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Cache-Control", "max-age=0")
            .addHeader("Connection", "keep-alive")
            .addHeader("Origin", "https://khmerdococr.pythonanywhere.com")
            .addHeader("Referer", "https://khmerdococr.pythonanywhere.com/")
            .addHeader("Sec-Fetch-Dest", "document")
            .addHeader("Sec-Fetch-Mode", "navigate")
            .addHeader("Sec-Fetch-Site", "same-origin")
            .addHeader("Sec-Fetch-User", "?1")
            .addHeader("Upgrade-Insecure-Requests", "1")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .addHeader("sec-ch-ua-mobile", "?1")
            .addHeader("sec-ch-ua-platform", "\"Android\"")
            .build()

        // Execute request in background thread
        Thread {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                
                runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        // Extract text between <pre> tags
                        val prePattern = "<pre>(.*?)</pre>".toRegex(RegexOption.DOT_MATCHES_ALL)
                        val matchResult = prePattern.find(responseBody)
                        
                        if (matchResult != null) {
                            val extractedText = matchResult.groupValues[1].trim()
                            textResult.text = extractedText
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Could not extract text from response",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Error: ${response.code} ${response.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Network error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun sendImageForDetection(imageFile: File) {
        Log.d(TAG, "Sending image for detection: ${imageFile.absolutePath}, exists: ${imageFile.exists()}, size: ${imageFile.length()}")

        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                imageFile.name,
                imageFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("https://khmerdococr.pythonanywhere.com/detect")
            .post(body)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .addHeader("Accept-Language", "km-KH,km;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Cache-Control", "max-age=0")
            .addHeader("Connection", "keep-alive")
            .addHeader("Origin", "https://khmerdococr.pythonanywhere.com")
            .addHeader("Referer", "https://khmerdococr.pythonanywhere.com/")
            .addHeader("Sec-Fetch-Dest", "document")
            .addHeader("Sec-Fetch-Mode", "navigate")
            .addHeader("Sec-Fetch-Site", "same-origin")
            .addHeader("Sec-Fetch-User", "?1")
            .addHeader("Upgrade-Insecure-Requests", "1")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .addHeader("sec-ch-ua-mobile", "?1")
            .addHeader("sec-ch-ua-platform", "\"Android\"")
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                
                runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        val fontPattern = "<p><strong>Detected Font:</strong>\\s*(.*?)</p>".toRegex()
                        val matchResult = fontPattern.find(responseBody)
                        
                        if (matchResult != null) {
                            val fontName = matchResult.groupValues[1].trim()
                            textResult.text = "Detected Font: $fontName"
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Could not extract font detection result",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Error: ${response.code} ${response.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Network error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openGallery() {
        pickImage.launch("image/*")
    }

    private fun openGalleryForDetection() {
        pickImageForDetection.launch("image/*")
    }

    private fun downloadDocxFile() {
        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://khmerdococr.pythonanywhere.com/download/page1.docx")
            .get()
            .addHeader("Accept", "*/*")
            .addHeader("Accept-Language", "km-KH,km;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Connection", "keep-alive")
            .addHeader("Referer", "https://khmerdococr.pythonanywhere.com/")
            .addHeader("Sec-Fetch-Dest", "document")
            .addHeader("Sec-Fetch-Mode", "navigate")
            .addHeader("Sec-Fetch-Site", "same-origin")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.let { responseBody ->
                        // Create downloads directory if it doesn't exist
                        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OCR_Results")
                        downloadsDir.mkdirs()

                        // Create file with timestamp
                        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
                        val file = File(downloadsDir, "OCR_Result_$timestamp.docx")

                        // Write the file
                        file.outputStream().use { fileOutputStream ->
                            responseBody.byteStream().use { inputStream ->
                                inputStream.copyTo(fileOutputStream)
                            }
                        }

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "File downloaded to: ${file.absolutePath}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Download failed: ${response.code} ${response.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Download error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_CODE = 1001
        private val outputDirectory: File
            get() {
                val mediaDir = File("/storage/emulated/0/Pictures/OCR_App").apply {
                    mkdirs()
                }
                return mediaDir
            }
    }
}