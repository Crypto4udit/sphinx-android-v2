@file:OptIn(ExperimentalCoroutinesApi::class)

package chat.sphinx.chat_common.ui.activity.call_activity

import android.view.View
import android.widget.ImageView
import chat.sphinx.chat_common.R
import chat.sphinx.chat_common.databinding.ParticipantItemBinding
import chat.sphinx.concept_image_loader.ImageLoader
import chat.sphinx.concept_image_loader.ImageLoaderOptions
import chat.sphinx.concept_image_loader.Transformation
import chat.sphinx.resources.setBackgroundRandomColor
import chat.sphinx.wrapper_common.util.getInitials
import com.github.ajalt.timberkt.Timber
import com.squareup.moshi.Moshi
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow
import io.matthewnelson.android_feature_screens.util.goneIfTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class ParticipantItem(
    private val room: Room,
    private val participant: Participant,
    private val speakerView: Boolean = false,
    val moshi: Moshi,
    val imageLoader: ImageLoader<ImageView>
) : BindableItem<ParticipantItemBinding>() {

    val imageLoaderDefaults by lazy {
        ImageLoaderOptions.Builder()
            .placeholderResId(chat.sphinx.resources.R.drawable.ic_media_library)
            .transformation(Transformation.CircleCrop)
            .build()
    }

    private var boundVideoTrack: VideoTrack? = null
    private var coroutineScope: CoroutineScope? = null

    override fun initializeViewBinding(view: View): ParticipantItemBinding {
        val binding = ParticipantItemBinding.bind(view)
        room.initVideoRenderer(binding.renderer)

        return binding
    }

    private fun ensureCoroutineScope() {
        if (coroutineScope == null) {
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
    }

    override fun bind(viewBinding: ParticipantItemBinding, position: Int) {
        ensureCoroutineScope()
        coroutineScope?.launch {
            participant::name.flow.collect { name ->
                if (name?.isNotEmpty() == true) {
                    viewBinding.identityText.text = name
                } else {
                    viewBinding.identityText.text = participant::identity.flow.value?.value
                }
            }
        }
        coroutineScope?.launch {
            participant::metadata.flow.collect { metadata ->
                viewBinding.profilePicture.visibility = View.GONE

                metadata?.toParticipantMetaDataOrNull(moshi)?.profilePictureUrl?.let {
                    viewBinding.profilePicture.visibility = View.VISIBLE

                    imageLoader.load(
                        viewBinding.profilePicture,
                        it,
                        imageLoaderDefaults,
                    )

                } ?: run {
                    val initials = participant.name?.getInitials()

                    viewBinding.profileInitials.apply {
                        visibility = View.VISIBLE
                        if (!initials.isNullOrEmpty()) {
                            text = initials.toUpperCase(Locale.getDefault())
                        }
                        setBackgroundRandomColor(R.drawable.circle_icon_4)
                    }
                }

            }
        }
        coroutineScope?.launch {
            participant::isSpeaking.flow.collect { isSpeaking ->
                if (isSpeaking) {
                    showFocus(viewBinding)
                } else {
                    hideFocus(viewBinding)
                }

                viewBinding.micOn.visibility = if (isSpeaking) View.INVISIBLE else View.VISIBLE
            }
        }
        coroutineScope?.launch {
            participant::audioTrackPublications.flow
                .flatMapLatest { tracks ->
                    val audioTrack = tracks.firstOrNull()?.first
                    if (audioTrack != null) {
                        audioTrack::muted.flow
                    } else {
                        flowOf(true)
                    }
                }
                .collect { muted ->
                    viewBinding.muteIndicator.visibility = if (muted) View.VISIBLE else View.INVISIBLE

                    viewBinding.micOn.visibility = if (muted) View.INVISIBLE else View.VISIBLE

                }
        }

        // observe videoTracks changes.
        val videoTrackPubFlow = participant::videoTrackPublications.flow
            .map { participant to it }
            .flatMapLatest { (participant, videoTracks) ->
                // Prioritize any screenshare streams.
                val trackPublication = participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                    ?: participant.getTrackPublication(Track.Source.CAMERA)
                    ?: videoTracks.firstOrNull()?.first

                flowOf(trackPublication)
            }

        coroutineScope?.launch {
            val videoTrackFlow = videoTrackPubFlow
                .flatMapLatestOrNull { pub -> pub::track.flow }

            // Configure video view with track
            launch {
                videoTrackFlow.collectLatest { videoTrack ->
                    setupVideoIfNeeded(videoTrack as? VideoTrack, viewBinding)
                }
            }

            // For local participants, mirror camera if using front camera.
            if (participant == room.localParticipant) {
                launch {
                    videoTrackFlow
                        .flatMapLatestOrNull { track -> (track as LocalVideoTrack)::options.flow }
                        .collectLatest { options ->
                            viewBinding.renderer.setMirror(options?.position == CameraPosition.FRONT)
                        }
                }
            }
        }

        // Handle muted changes
        coroutineScope?.launch {
            videoTrackPubFlow
                .flatMapLatestOrNull { pub -> pub::muted.flow }
                .collectLatest { muted ->
                    viewBinding.renderer.visibleOrInvisible(!(muted ?: true))
                }
        }
        val existingTrack = getVideoTrack()
        if (existingTrack != null) {
            setupVideoIfNeeded(existingTrack, viewBinding)
        }
    }

    private fun getVideoTrack(): VideoTrack? {
        return participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    }

    private fun setupVideoIfNeeded(videoTrack: VideoTrack?, viewBinding: ParticipantItemBinding) {
        if (boundVideoTrack == videoTrack) {
            return
        }
        boundVideoTrack?.removeRenderer(viewBinding.renderer)
        boundVideoTrack = videoTrack
        Timber.v { "adding renderer to $videoTrack" }
        videoTrack?.addRenderer(viewBinding.renderer)
    }

    override fun unbind(viewHolder: GroupieViewHolder<ParticipantItemBinding>) {
        coroutineScope?.cancel()
        coroutineScope = null
        super.unbind(viewHolder)
        boundVideoTrack?.removeRenderer(viewHolder.binding.renderer)
        boundVideoTrack = null
    }

    override fun getLayout(): Int =
        if (speakerView) {
            R.layout.speaker_view
        } else {
            R.layout.participant_item
        }
}

private fun View.visibleOrGone(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.GONE
    }
}

private fun View.visibleOrInvisible(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.INVISIBLE
    }
}

private fun showFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.VISIBLE
    binding.speakingNow.visibility = View.VISIBLE
}

private fun hideFocus(binding: ParticipantItemBinding) {
    binding.speakingIndicator.visibility = View.INVISIBLE
    binding.speakingNow.visibility = View.INVISIBLE

}

private inline fun <T, R> Flow<T?>.flatMapLatestOrNull(
    crossinline transform: suspend (value: T) -> Flow<R>,
): Flow<R?> {
    return flatMapLatest {
        if (it == null) {
            flowOf(null)
        } else {
            transform(it)
        }
    }
}