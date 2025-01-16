package chat.sphinx.chat_common.ui.activity.call_activity

import android.app.Activity
import android.app.PictureInPictureParams
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xwray.groupie.GroupieAdapter
import chat.sphinx.chat_common.R
import chat.sphinx.chat_common.databinding.CallActivityBinding
import chat.sphinx.chat_common.ui.activity.call_activity.dialog.showDebugMenuDialog
import chat.sphinx.chat_common.ui.activity.call_activity.dialog.showSelectAudioDeviceDialog
import chat.sphinx.concept_image_loader.ImageLoader
import com.squareup.moshi.Moshi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    private val moshi: Moshi by lazy {
        Moshi.Builder().build()
    }

    @Inject
    @Suppress("ProtectedInFinal")
    protected lateinit var imageLoader: ImageLoader<ImageView>

    private val viewModel: CallViewModel by viewModelByFactory {
        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")

        CallViewModel(
            url = args.url,
            token = args.token,
            e2ee = args.e2eeOn,
            e2eeKey = args.e2eeKey,
            videoEnabled = args.videoEnabled,
            stressTest = args.stressTest,
            application = application
        )
    }
    private lateinit var binding: CallActivityBinding
    private val screenCaptureIntentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val resultCode = result.resultCode
            val data = result.data
            if (resultCode != Activity.RESULT_OK || data == null) {
                return@registerForActivityResult
            }
            viewModel.startScreenCapture(data)
        }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        requestNeededPermissions {
            viewModel.reconnect()
        }

        // Audience row setup
        val audienceAdapter = GroupieAdapter()
        binding.audienceRow.apply {
            layoutManager = LinearLayoutManager(this@CallActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = audienceAdapter
        }

        lifecycleScope.launchWhenCreated {
            viewModel.participants
                .collect { participants ->
                    val items = participants.map { participant ->
                        ParticipantItem(
                            viewModel.room,
                            participant,
                            moshi = moshi,
                            imageLoader = imageLoader
                        )
                    }
                    audienceAdapter.update(items)
                }
        }

        // speaker view setup
        val speakerAdapter = GroupieAdapter()
        binding.speakerView.apply {
            layoutManager = LinearLayoutManager(this@CallActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = speakerAdapter
        }
        lifecycleScope.launchWhenCreated {
            viewModel.primarySpeaker.collectLatest { speaker ->
                val items = listOfNotNull(speaker)
                    .map { participant ->
                        ParticipantItem(
                            viewModel.room,
                            participant,
                            speakerView = true,
                            moshi = moshi,
                            imageLoader = imageLoader
                        )
                    }
                speakerAdapter.update(items)
            }
        }

        // Controls setup
        viewModel.cameraEnabled.observe(this) { enabled ->
            binding.camera.setOnClickListener { viewModel.setCameraEnabled(!enabled) }
            binding.camera.setImageResource(
                if (enabled) {
                    R.drawable.camera
                } else {
                    R.drawable.camera_off
                }
            )

            binding.camera.backgroundTintList = if (enabled) {
                null
            } else {

                ContextCompat.getColorStateList(this, R.color.toggle_icons)
            }


            binding.flipCamera.isEnabled = enabled
        }
        viewModel.micEnabled.observe(this) { enabled ->
            binding.mic.setOnClickListener { viewModel.setMicEnabled(!enabled) }
            binding.mic.setImageResource(
                if (enabled) {
                    R.drawable.mic
                } else {
                    R.drawable.mic_off
                }
            )


            binding.mic.backgroundTintList = if (enabled) {
                null
            } else {

                ContextCompat.getColorStateList(this, R.color.toggle_icons)
            }
        }


        binding.flipCamera.setOnClickListener { viewModel.flipCamera() }
        /* viewModel.screenshareEnabled.observe(this) { enabled ->
            binding.screenShare.setOnClickListener {
                if (enabled) {
                    viewModel.stopScreenCapture()
                } else {
                    requestMediaProjection()
                }
            }
            binding.screenShare.setImageResource(
                if (enabled) {
                    R.drawable.baseline_cast_connected_24
                } else {
                    R.drawable.baseline_cast_24
                },
            )
        }

        binding.message.setOnClickListener {
            val editText = EditText(this)
            AlertDialog.Builder(this)
                .setTitle("Send Message")
                .setView(editText)
                .setPositiveButton("Send") { dialog, _ ->
                    viewModel.sendData(editText.text?.toString() ?: "")
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .create()
                .show()
        }

        viewModel.enhancedNsEnabled.observe(this) { enabled ->
            binding.enhancedNs.visibility = if (enabled) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        binding.enhancedNs.setOnClickListener {
            showAudioProcessorSwitchDialog(viewModel)
        }*/

        binding.exit.setOnClickListener { finish() }


        binding.audioSelect.setOnClickListener {
            showSelectAudioDeviceDialog(viewModel)
        }
        /* lifecycleScope.launchWhenCreated {
            viewModel.permissionAllowed.collect { allowed ->
                val resource = if (allowed) R.drawable.account_cancel_outline else R.drawable.account_cancel
                binding.permissions.setImageResource(resource)
            }
        }
        binding.permissions.setOnClickListener {
            viewModel.toggleSubscriptionPermissions()
        }*/

        binding.debugMenu.setOnClickListener {
            showDebugMenuDialog(viewModel)
        }

        binding.pipMode.setOnClickListener {
            enterPictureInPictureMode()
        }

        binding.listParticipants.setOnClickListener {
            lifecycleScope.launchWhenCreated {
                // Collect participants list only when the button is clicked
                viewModel.participants.collect { participants ->

                    // Only show BottomSheet when user clicks the button
                    val bottomSheet = ParticipantsBottomSheetFragment(participants)
                    bottomSheet.show(supportFragmentManager, bottomSheet.tag)
                }
            }
        }


        lifecycleScope.launchWhenCreated {
            viewModel.participants.collect { participants ->
                // Update the participant count on the badge
                val participantCountBadge = findViewById<TextView>(R.id.participantCountBadge)
                if (participants.isNotEmpty()) {
                    // Show badge and set the count
                    participantCountBadge.visibility = View.VISIBLE
                    participantCountBadge.text = participants.size.toString()
                } else {
                    // Hide badge if no participants
                    participantCountBadge.visibility = View.GONE
                }
            }
        }

    }

    override fun onUserLeaveHint() {
        // Trigger PiP mode when the user presses the home button or switches apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(9, 16) // Aspect ratio of the PiP window
            val pipParams = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(pipParams)

            binding.controlsBox.visibility = android.view.View.GONE
            binding.controlsBox2.visibility = android.view.View.GONE
            binding.audienceRow.visibility = android.view.View.GONE

        }
    }

    // Function to start Picture-in-Picture mode
    @Deprecated("Deprecated in Java")
    override fun enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(9, 16) // Define the aspect ratio of the PiP window
            val pipParams = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)  // Set aspect ratio
                .build()
            enterPictureInPictureMode(pipParams)

            binding.controlsBox.visibility = android.view.View.GONE
            binding.controlsBox2.visibility = android.view.View.GONE
            binding.audienceRow.visibility = android.view.View.GONE

        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launchWhenResumed {
            viewModel.error.collect {
                if (it != null) {
                    Toast.makeText(this@CallActivity, "Error: $it", Toast.LENGTH_LONG).show()
                    viewModel.dismissError()
                }
            }
        }

        lifecycleScope.launchWhenResumed {
            viewModel.dataReceived.collect {
                Toast.makeText(this@CallActivity, "Data received: $it", Toast.LENGTH_LONG).show()
            }
        }

        binding.controlsBox.visibility = View.VISIBLE
        binding.controlsBox2.visibility = View.VISIBLE
        binding.audienceRow.visibility = View.VISIBLE
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureIntentLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        binding.audienceRow.adapter = null
        binding.speakerView.adapter = null
        super.onDestroy()
    }

    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(
        val url: String,
        val token: String,
        val e2eeKey: String,
        val e2eeOn: Boolean,
        val stressTest: StressTest,
        val videoEnabled: Boolean
    ) : Parcelable
}
