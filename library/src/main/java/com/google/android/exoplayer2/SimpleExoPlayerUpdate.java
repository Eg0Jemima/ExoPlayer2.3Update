/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntDef;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link ExoPlayer} implementation that uses default {@link Renderer} components. Instances can
 * be obtained from {@link ExoPlayerFactory}.
 */
@TargetApi(16)
public class SimpleExoPlayerUpdate implements ExoPlayer {

    /**
     * A listener for video rendering information from a {@link SimpleExoPlayer}.
     */
    public interface VideoListener {

        /**
         * Called each time there's a change in the size of the video being rendered.
         *
         * @param width The video width in pixels.
         * @param height The video height in pixels.
         * @param unappliedRotationDegrees For videos that require a rotation, this is the clockwise
         *     rotation in degrees that the application should apply for the video for it to be rendered
         *     in the correct orientation. This value will always be zero on API levels 21 and above,
         *     since the renderer will apply all necessary rotations internally. On earlier API levels
         *     this is not possible. Applications that use {@link android.view.TextureView} can apply
         *     the rotation by calling {@link android.view.TextureView#setTransform}. Applications that
         *     do not expect to encounter rotated videos can safely ignore this parameter.
         * @param pixelWidthHeightRatio The width to height ratio of each pixel. For the normal case
         *     of square pixels this will be equal to 1.0. Different values are indicative of anamorphic
         *     content.
         */
        void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                float pixelWidthHeightRatio);

        /**
         * Called when a frame is rendered for the first time since setting the surface, and when a
         * frame is rendered for the first time since a video track was selected.
         */
        void onRenderedFirstFrame();

    }

    /**
     * Modes for using extension renderers.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({EXTENSION_RENDERER_MODE_OFF, EXTENSION_RENDERER_MODE_ON, EXTENSION_RENDERER_MODE_PREFER})
    public @interface ExtensionRendererMode {}
    /**
     * Do not allow use of extension renderers.
     */
    public static final int EXTENSION_RENDERER_MODE_OFF = 0;
    /**
     * Allow use of extension renderers. Extension renderers are indexed after core renderers of the
     * same type. A {@link TrackSelector} that prefers the first suitable renderer will therefore
     * prefer to use a core renderer to an extension renderer in the case that both are able to play
     * a given track.
     */
    public static final int EXTENSION_RENDERER_MODE_ON = 1;
    /**
     * Allow use of extension renderers. Extension renderers are indexed before core renderers of the
     * same type. A {@link TrackSelector} that prefers the first suitable renderer will therefore
     * prefer to use an extension renderer to a core renderer in the case that both are able to play
     * a given track.
     */
    public static final int EXTENSION_RENDERER_MODE_PREFER = 2;

    private static final String TAG = "SimpleExoPlayer";
    protected static final int MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50;

    private final ExoPlayer player;
    private final Renderer[] renderers;
    private final ComponentListener componentListener;
    private final Handler mainHandler;
    private final int videoRendererCount;
    private final int audioRendererCount;

    private Format videoFormat;
    private Format audioFormat;

    private Surface surface;
    private boolean ownsSurface;
    @C.VideoScalingMode
    private int videoScalingMode;
    private SurfaceHolder surfaceHolder;
    private TextureView textureView;
    private TextRenderer.Output textOutput;
    private MetadataRenderer.Output metadataOutput;
    private VideoListener videoListener;
    private AudioRendererEventListener audioDebugListener;
    private VideoRendererEventListener videoDebugListener;
    private DecoderCounters videoDecoderCounters;
    private DecoderCounters audioDecoderCounters;
    private int audioSessionId;
    @C.StreamType
    private int audioStreamType;
    private float audioVolume;
    private PlaybackParamsHolder playbackParamsHolder;

    //Old ExoDemoPlayer Variables needed
    String 				currentMovieFilename;

    boolean				playbackFinished = true;
    boolean				playbackFailed = false;

    private boolean 	waitingForSeek = false;
    private boolean 	haveSeekWaiting = false;
    private long 		nextSeekPosition = 0;
    private long       	startTime = 0;

    private int			textureId = -1;
    SurfaceTexture 		movieTexture = null;

    protected SimpleExoPlayerUpdate(Context context, TrackSelector trackSelector, LoadControl loadControl,
                              DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                              @ExtensionRendererMode int extensionRendererMode, long allowedVideoJoiningTimeMs) {
        mainHandler = new Handler();
        componentListener = new ComponentListener();

        // Build the renderers.
        ArrayList<Renderer> renderersList = new ArrayList<>();
        buildRenderers(context, mainHandler, drmSessionManager, extensionRendererMode,
                allowedVideoJoiningTimeMs, renderersList);
        renderers = renderersList.toArray(new Renderer[renderersList.size()]);

        // Obtain counts of video and audio renderers.
        int videoRendererCount = 0;
        int audioRendererCount = 0;
        for (Renderer renderer : renderers) {
            switch (renderer.getTrackType()) {
                case C.TRACK_TYPE_VIDEO:
                    videoRendererCount++;
                    break;
                case C.TRACK_TYPE_AUDIO:
                    audioRendererCount++;
                    break;
            }
        }
        this.videoRendererCount = videoRendererCount;
        this.audioRendererCount = audioRendererCount;

        // Set initial values.
        audioVolume = 1;
        audioSessionId = C.AUDIO_SESSION_ID_UNSET;
        audioStreamType = C.STREAM_TYPE_DEFAULT;
        videoScalingMode = C.VIDEO_SCALING_MODE_DEFAULT;

        // Build the player and associated objects.
        player = new ExoPlayerImpl(renderers, trackSelector, loadControl);
    }

    /**
     * Sets the video scaling mode.
     * <p>
     * Note that the scaling mode only applies if a {@link MediaCodec}-based video {@link Renderer} is
     * enabled and if the output surface is owned by a {@link android.view.SurfaceView}.
     *
     * @param videoScalingMode The video scaling mode.
     */
    public void setVideoScalingMode(@C.VideoScalingMode int videoScalingMode) {
        this.videoScalingMode = videoScalingMode;
        ExoPlayerMessage[] messages = new ExoPlayerMessage[videoRendererCount];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_VIDEO) {
                messages[count++] = new ExoPlayerMessage(renderer, C.MSG_SET_SCALING_MODE,
                        videoScalingMode);
            }
        }
        player.sendMessages(messages);
    }

    /**
     * Returns the video scaling mode.
     */
    public @C.VideoScalingMode int getVideoScalingMode() {
        return videoScalingMode;
    }

    /**
     * Clears any {@link Surface}, {@link SurfaceHolder}, {@link SurfaceView} or {@link TextureView}
     * currently set on the player.
     */
    public void clearVideoSurface() {
        setVideoSurface(null);
    }

    /**
     * Sets the {@link Surface} onto which video will be rendered. The caller is responsible for
     * tracking the lifecycle of the surface, and must clear the surface by calling
     * {@code setVideoSurface(null)} if the surface is destroyed.
     * <p>
     * If the surface is held by a {@link SurfaceView}, {@link TextureView} or {@link SurfaceHolder}
     * then it's recommended to use {@link #setVideoSurfaceView(SurfaceView)},
     * {@link #setVideoTextureView(TextureView)} or {@link #setVideoSurfaceHolder(SurfaceHolder)}
     * rather than this method, since passing the holder allows the player to track the lifecycle of
     * the surface automatically.
     *
     * @param surface The {@link Surface}.
     */
    public void setVideoSurface(Surface surface) {
        removeSurfaceCallbacks();
        setVideoSurfaceInternal(surface, false);
    }

    /**
     * Sets the {@link SurfaceHolder} that holds the {@link Surface} onto which video will be
     * rendered. The player will track the lifecycle of the surface automatically.
     *
     * @param surfaceHolder The surface holder.
     */
    public void setVideoSurfaceHolder(SurfaceHolder surfaceHolder) {
        removeSurfaceCallbacks();
        this.surfaceHolder = surfaceHolder;
        if (surfaceHolder == null) {
            setVideoSurfaceInternal(null, false);
        } else {
            setVideoSurfaceInternal(surfaceHolder.getSurface(), false);
            surfaceHolder.addCallback(componentListener);
        }
    }

    /**
     * Sets the {@link SurfaceView} onto which video will be rendered. The player will track the
     * lifecycle of the surface automatically.
     *
     * @param surfaceView The surface view.
     */
    public void setVideoSurfaceView(SurfaceView surfaceView) {
        setVideoSurfaceHolder(surfaceView.getHolder());
    }

    /**
     * Sets the {@link TextureView} onto which video will be rendered. The player will track the
     * lifecycle of the surface automatically.
     *
     * @param textureView The texture view.
     */
    public void setVideoTextureView(TextureView textureView) {
        removeSurfaceCallbacks();
        this.textureView = textureView;
        if (textureView == null) {
            setVideoSurfaceInternal(null, true);
        } else {
            if (textureView.getSurfaceTextureListener() != null) {
                Log.w(TAG, "Replacing existing SurfaceTextureListener.");
            }
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            setVideoSurfaceInternal(surfaceTexture == null ? null : new Surface(surfaceTexture), true);
            textureView.setSurfaceTextureListener(componentListener);
        }
    }

    //Added set surface - EJ Mann
    public void setSurface(Surface s) {setVideoSurfaceInternal(s, true); }

    /**
     * Sets the stream type for audio playback (see {@link C.StreamType} and
     * {@link android.media.AudioTrack#AudioTrack(int, int, int, int, int, int)}). If the stream type
     * is not set, audio renderers use {@link C#STREAM_TYPE_DEFAULT}.
     * <p>
     * Note that when the stream type changes, the AudioTrack must be reinitialized, which can
     * introduce a brief gap in audio output. Note also that tracks in the same audio session must
     * share the same routing, so a new audio session id will be generated.
     *
     * @param audioStreamType The stream type for audio playback.
     */
    public void setAudioStreamType(@C.StreamType int audioStreamType) {
        this.audioStreamType = audioStreamType;
        ExoPlayerMessage[] messages = new ExoPlayerMessage[audioRendererCount];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) {
                messages[count++] = new ExoPlayerMessage(renderer, C.MSG_SET_STREAM_TYPE, audioStreamType);
            }
        }
        player.sendMessages(messages);
    }

    /**
     * Returns the stream type for audio playback.
     */
    public @C.StreamType int getAudioStreamType() {
        return audioStreamType;
    }

    /**
     * Sets the audio volume, with 0 being silence and 1 being unity gain.
     *
     * @param audioVolume The audio volume.
     */
    public void setVolume(float audioVolume) {
        this.audioVolume = audioVolume;
        ExoPlayerMessage[] messages = new ExoPlayerMessage[audioRendererCount];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) {
                messages[count++] = new ExoPlayerMessage(renderer, C.MSG_SET_VOLUME, audioVolume);
            }
        }
        player.sendMessages(messages);
    }

    /**
     * Returns the audio volume, with 0 being silence and 1 being unity gain.
     */
    public float getVolume() {
        return audioVolume;
    }

    /**
     * Sets the {@link PlaybackParams} governing audio playback.
     *
     * @param params The {@link PlaybackParams}, or null to clear any previously set parameters.
     */
    @TargetApi(23)
    public void setPlaybackParams(PlaybackParams params) {
        if (params != null) {
            // The audio renderers will call this on the playback thread to ensure they can query
            // parameters without failure. We do the same up front, which is redundant except that it
            // ensures an immediate call to getPlaybackParams will retrieve the instance with defaults
            // allowed, rather than this change becoming visible sometime later once the audio renderers
            // receive the parameters.
            params.allowDefaults();
            playbackParamsHolder = new PlaybackParamsHolder(params);
        } else {
            playbackParamsHolder = null;
        }
        ExoPlayerMessage[] messages = new ExoPlayerMessage[audioRendererCount];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_AUDIO) {
                messages[count++] = new ExoPlayerMessage(renderer, C.MSG_SET_PLAYBACK_PARAMS, params);
            }
        }
        player.sendMessages(messages);
    }

    /**
     * Returns the {@link PlaybackParams} governing audio playback, or null if not set.
     */
    @TargetApi(23)
    public PlaybackParams getPlaybackParams() {
        return playbackParamsHolder == null ? null : playbackParamsHolder.params;
    }

    /**
     * Returns the video format currently being played, or null if no video is being played.
     */
    public Format getVideoFormat() {
        return videoFormat;
    }

    /**
     * Returns the audio format currently being played, or null if no audio is being played.
     */
    public Format getAudioFormat() {
        return audioFormat;
    }

    /**
     * Returns the audio session identifier, or {@link C#AUDIO_SESSION_ID_UNSET} if not set.
     */
    public int getAudioSessionId() {
        return audioSessionId;
    }

    /**
     * Returns {@link DecoderCounters} for video, or null if no video is being played.
     */
    public DecoderCounters getVideoDecoderCounters() {
        return videoDecoderCounters;
    }

    /**
     * Returns {@link DecoderCounters} for audio, or null if no audio is being played.
     */
    public DecoderCounters getAudioDecoderCounters() {
        return audioDecoderCounters;
    }

    /**
     * Sets a listener to receive video events.
     *
     * @param listener The listener.
     */
    public void setVideoListener(VideoListener listener) {
        videoListener = listener;
    }

    /**
     * Sets a listener to receive debug events from the video renderer.
     *
     * @param listener The listener.
     */
    public void setVideoDebugListener(VideoRendererEventListener listener) {
        videoDebugListener = listener;
    }

    /**
     * Sets a listener to receive debug events from the audio renderer.
     *
     * @param listener The listener.
     */
    public void setAudioDebugListener(AudioRendererEventListener listener) {
        audioDebugListener = listener;
    }

    /**
     * Sets an output to receive text events.
     *
     * @param output The output.
     */
    public void setTextOutput(TextRenderer.Output output) {
        textOutput = output;
    }

    /**
     * Sets a listener to receive metadata events.
     *
     * @param output The output.
     */
    public void setMetadataOutput(MetadataRenderer.Output output) {
        metadataOutput = output;
    }

    // ExoPlayer implementation

    @Override
    public void addListener(EventListener listener) {
        player.addListener(listener);
    }

    @Override
    public void removeListener(EventListener listener) {
        player.removeListener(listener);
    }

    @Override
    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    @Override
    public void prepare(MediaSource mediaSource) {
        player.prepare(mediaSource);
    }

    @Override
    public void prepare(MediaSource mediaSource, boolean resetPosition, boolean resetState) {
        player.prepare(mediaSource, resetPosition, resetState);
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    @Override
    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }

    @Override
    public boolean isLoading() {
        return player.isLoading();
    }

    @Override
    public void seekToDefaultPosition() {
        player.seekToDefaultPosition();
    }

    @Override
    public void seekToDefaultPosition(int windowIndex) {
        player.seekToDefaultPosition(windowIndex);
    }

    @Override
    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    @Override
    public void seekTo(int windowIndex, long positionMs) {
        player.seekTo(windowIndex, positionMs);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void release() {
        player.release();
        removeSurfaceCallbacks();
        if (surface != null) {
            if (ownsSurface) {
                surface.release();
            }
            surface = null;
        }
    }

    @Override
    public void sendMessages(ExoPlayerMessage... messages) {
        player.sendMessages(messages);
    }

    @Override
    public void blockingSendMessages(ExoPlayerMessage... messages) {
        player.blockingSendMessages(messages);
    }

    @Override
    public int getRendererCount() {
        return player.getRendererCount();
    }

    @Override
    public int getRendererType(int index) {
        return player.getRendererType(index);
    }

    @Override
    public TrackGroupArray getCurrentTrackGroups() {
        return player.getCurrentTrackGroups();
    }

    @Override
    public TrackSelectionArray getCurrentTrackSelections() {
        return player.getCurrentTrackSelections();
    }

    @Override
    public Timeline getCurrentTimeline() {
        return player.getCurrentTimeline();
    }

    @Override
    public Object getCurrentManifest() {
        return player.getCurrentManifest();
    }

    @Override
    public int getCurrentPeriodIndex() {
        return player.getCurrentPeriodIndex();
    }

    @Override
    public int getCurrentWindowIndex() {
        return player.getCurrentWindowIndex();
    }

    @Override
    public long getDuration() {
        return player.getDuration();
    }

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public long getBufferedPosition() {
        return player.getBufferedPosition();
    }

    @Override
    public int getBufferedPercentage() {
        return player.getBufferedPercentage();
    }

    @Override
    public boolean isCurrentWindowDynamic() {
        return player.isCurrentWindowDynamic();
    }

    @Override
    public boolean isCurrentWindowSeekable() {
        return player.isCurrentWindowSeekable();
    }

    public long getBandWidth(){
        if(player != null){
            try{
                return 0; //TODO BIG Time!
            }catch(IllegalStateException ise){
                Log.d(TAG, "getBandwidth(): Caught illegalStateException");
                return 0;
            }
        }
        return 0;
    }

    public String getPlayerState(){
        String text = "";
        switch(player.getPlaybackState()) {
            case ExoPlayer.STATE_BUFFERING:
                text += "buffering";
                break;
            case ExoPlayer.STATE_ENDED:
                text += "ended";
                break;
            case ExoPlayer.STATE_IDLE:
                text += "idle";
                break;
            case ExoPlayer.STATE_READY:
                text += "ready";
                break;
            default:
                text += "unknown";
                break;
        }
        return text;
    }

    public void seekDelta( int deltaMilliseconds )
    {
        try
        {
            if ( player != null )
            {
                boolean wasPlaying = isPlaying();
                if ( wasPlaying )
                {
                    player.pause();
                }

                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                long newPosition = position + deltaMilliseconds;
                if ( newPosition >= duration )
                {
                    // pause if seeking past end
                    Log.d( TAG, "seek past end" );
                    player.seekTo( duration );
                    return;
                }
                if ( newPosition < 0 )
                {
                    newPosition = 0;
                }

                if ( waitingForSeek )
                {
                    haveSeekWaiting = true;
                    nextSeekPosition = newPosition;
                }
                else
                {
                    waitingForSeek = true;
                    Log.d( TAG, "seek started" );
                    player.seekTo( newPosition );
                }

                if ( wasPlaying )
                {
                    player.start();
                }
            }
        }

        catch( IllegalStateException ise )
        {
            // Can be thrown by the media player if state changes while we're being
            // queued for execution on the main thread
            Log.d( TAG, "seekDelta(): Caught illegalStateException" );
        }
    }

    public void setPosition( int positionMilliseconds )
    {
        try
        {
            if ( player != null )
            {
                boolean wasPlaying = player.isPlaying;
                if ( wasPlaying )
                {
                    player.pause();
                }
                long duration = player.getDuration();
                int newPosition = positionMilliseconds;
                if ( newPosition >= duration )
                {
                    // pause if seeking past end
                    Log.d( TAG, "seek past end" );
                    player.seekTo( duration );
                    return;
                }
                if ( newPosition < 0 )
                {
                    newPosition = 0;
                }

                // HACK: what seek listener for exoplayer?
/*
				if ( waitingForSeek )
				{
					haveSeekWaiting = true;
					nextSeekPosition = newPosition;
				}
				else
				{
					waitingForSeek = true;
*/
                // HACK: just do the seek
                {
                    Log.d(TAG, "seek started");
                    player.seekTo( newPosition );
                }

                if ( wasPlaying )
                {
                    player.start();
                }
            }
        }

        catch( IllegalStateException ise )
        {
            // Can be thrown by the media player if state changes while we're being
            // queued for execution on the main thread
            Log.d( TAG, "setPosition(): Caught illegalStateException" );
        }
    }

    public long getPosition()
    {
        if ( player != null )
        {
            try
            {
                return player.getCurrentPosition();
            }
            catch( IllegalStateException ise )
            {
                // Can be thrown by the media player if state changes while we're being
                // queued for execution on the main thread
                Log.d( TAG, "getPosition(): Caught illegalStateException" );
                return 0;
            }
        }
        return 0;
    }

    public boolean isPlaying()
    {
        if ( player != null )
        {
            try
            {
                return player.isPlaying();
            }

            catch ( IllegalStateException t )
            {
                Log.e(TAG, "isPlaying() caught illegalStateException" );
                return false;
            }
        }
        return false;
    }

    public boolean isPlaybackFinished()
    {
        return playbackFinished;
    }

    public boolean hadPlaybackError()
    {
        return playbackFailed;
    }

    public void startMovie(String pathName) {
        startMovieLocal(pathName);
    }

    public void updateSurface() {
        Log.v(TAG, "updateSurface");
        if (movieTexture != null && isPlaying()) {
            synchronized( this )
            {
                try { movieTexture.attachToGLContext(textureId); }
                catch (Exception e) { Log.e(TAG, "!!! movieTexture.attachToGLContext " + e.getMessage()); }

                try { movieTexture.updateTexImage(); }
                catch (Exception e) { Log.e(TAG, "!!! movieTexture.updateTexImage " + e.getMessage()); }

                try { movieTexture.detachFromGLContext(); }
                catch (Exception e) { Log.e(TAG, "!!! movieTexture.detachFromGLContext " + e.getMessage()); }
            }
        }
        Log.v(TAG, "updateSurface done");
    }

    // in ExoPlayer 1.5.3, the samples have changed so they all use the same MPD URL,
    // but vary the contentId and sometimes provider (usually widevine_test),
    // where the contentId identifies the conditions we want to test...
    // so the question is now how to represent that?

    private static final String WIDEVINE_GTS_DEFAULT_BASE_URI
            = "https://proxy.uat.widevine.com/proxy?video_id=";
    // provider will now be appended separately
    private static final String YOUTUBE_MANIFEST_VIDEO_ID_PREFIX
            = "://www.youtube.com/api/manifest/dash/id/";

    private static String getContentIdFromUrl(String url) {
        // for DRM content (YouTube) this needs to include the video_id
        int idx = url.indexOf(YOUTUBE_MANIFEST_VIDEO_ID_PREFIX);
        if (idx != -1) {
            idx += YOUTUBE_MANIFEST_VIDEO_ID_PREFIX.length();
            return WIDEVINE_GTS_DEFAULT_BASE_URI + url.substring(idx, url.indexOf('/', idx+1));
        }

        if (-1 != url.indexOf("://storage.googleapis.com/wvmedia/cenc/")){
            return WIDEVINE_GTS_DEFAULT_BASE_URI;
        } else
        if (-1 != url.indexOf("://demo.unified-streaming.com/video/widevine/")) {
            // doesn't work?
            return "http://wv-ref-eme-player.appspot.com/proxy";
        }

        return WIDEVINE_GTS_DEFAULT_BASE_URI;

    }

    private static String getProviderFromUrl(String url) {
        // assume we're always going to return URL scheme in contentId,
        // so we should return fully built suffix

        int idx = url.indexOf(YOUTUBE_MANIFEST_VIDEO_ID_PREFIX);
        if (idx != -1) {
            return "&provider=YouTube";
        }

        if (-1 != url.indexOf("://storage.googleapis.com/wvmedia/cenc/")){
            return "&provider=widevine_test";
        }
        else
        if (-1 != url.indexOf("://demo.unified-streaming.com/video/widevine/")) {
            return ""; // what's the right value to use?
        }

        return "&provider=widevine_test"; // FIXME
    }

    private void startMovieLocal(final String pathName) //, final boolean resumePlayback, boolean isEncrypted, final boolean loop )
    {
        Log.v(TAG, "startMovie " + pathName); // + " resume " + resumePlayback );

        // I think we need to fix the way the surface is allocated...
        // do it on the calling thread

        // Have native code pause any playing movie,
        // allocate a new external texture,
        // and create a surfaceTexture with it.
        //movieTexture = new SurfaceTexture(textureId); //nativePrepareNewVideo( appPtr );
        // if we weren't given a surface, assume we were given a texture ID
        if (textureId != -1 && surface == null) {
            movieTexture = new SurfaceTexture(textureId);
/* doesn't help...
			movieTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
				@Override
				public void onFrameAvailable(SurfaceTexture surfaceTexture) {
					Log.v(TAG, "onFrameAvailable... updateTexImage()");
					surfaceTexture.updateTexImage();
				}
			}, new Handler()); // handler is on the current thread
*/
            surface = new Surface( movieTexture );
        }

        final SimpleExoPlayerUpdate self = this;

        synchronized( this )
        {
            requestAudioFocus();

            playbackFinished = false;
            playbackFailed = false;

            waitingForSeek = false;
            haveSeekWaiting = false;
            nextSeekPosition = 0;

            currentMovieFilename = pathName;

            if (player != null)
            {
                player.release();
            }

            // for some reason this needs to be on UI thread
            try {
                final AtomicBoolean done = new AtomicBoolean(false);
                final Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        synchronized(this) {

                            Log.v( TAG, "DemoPlayer.create" );

                            Uri parsed = Uri.parse(currentMovieFilename);
                            String path = parsed.getPath();
                            player = new SimpleExoPlayerUpdate(getRendererBuilder(
                                    context,
                                    // NO -- for DRM content (YouTube) this needs to be the video_id, not the URL... currentMovieFilename,
                                    getContentIdFromUrl(currentMovieFilename),
                                    getProviderFromUrl(currentMovieFilename),
                                    parsed,
                                    // FIXME: do a better job figuring out type
                                    path.endsWith(".mpd") ? DemoUtil.TYPE_DASH :
                                            path.endsWith(".ism") ? DemoUtil.TYPE_SS :
                                                    path.endsWith(".m3u8") ? DemoUtil.TYPE_HLS :
                                                            path.endsWith(".m4a") ? DemoUtil.TYPE_MP4 :
                                                                    path.endsWith(".mp4") ? DemoUtil.TYPE_MP4 :
                                                                            path.endsWith(".f4v") ? DemoUtil.TYPE_MP4 :
                                                                                    path.endsWith(".mp3") ? DemoUtil.TYPE_MP3 :
                                                                                            path.endsWith(".ts") ? DemoUtil.TYPE_TS :
                                                                                                    path.endsWith(".aac") ? DemoUtil.TYPE_AAC :
                                                                                                            path.endsWith(".webm") ? DemoUtil.TYPE_WEBM :
                                                                                                                    // common MSFT/Azure-ism
                                                                                                                    path.contains("anifest(format=m3u8") ? DemoUtil.TYPE_HLS :
                                                                                                                            // one Live Extra convention
                                                                                                                            currentMovieFilename.contains("nbcsn_liveextra_ios") ? DemoUtil.TYPE_HLS :
                                                                                                                                    // thePlatform redirect link convention
                                                                                                                                    currentMovieFilename.contains("formats=m3u,mpeg4&redirect=true") ? DemoUtil.TYPE_HLS :
                                                                                                                                            path.startsWith("http") ? DemoUtil.TYPE_DASH :
                                                                                                                                                    -1 /* should throw exception, since there is no longer a DemoUtil.TYPE_OTHER */));
                            player.addListener(self);
                            // TODO... InfoListener
                            player.setCaptionListener(self);
                            player.setMetadataListener(self);

                            try
                            {
                                Log.v(TAG, "DemoPlayer.prepare");
                                player.prepare();
                            }
                            catch ( IllegalStateException t )
                            {
                                Fail( "DemoPlayer.prepare threw illegalStateException" );
                                return;
                            }


                            done.set(true);
                            notify();
                        }
                    }
                };
                new Handler(Looper.getMainLooper()).post(task);
                synchronized(task) {
                    while(!done.get()) {
                        task.wait();
                    }
                    Log.d(TAG, "done with MainLooper stuff!");

                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread got interrupted while waiting for posted runnable to finish its task");
            }

/*
// need to replace this for ExoPlayer

			// ensure we're at max volume
			mediaPlayer.setVolume( 1.0f, 1.0f );
*/
            player.setSurface(surface);

            Log.v( TAG, "DemoPlayer.start" );
/*
			// If this movie has a saved position, seek there before starting
			Log.d( TAG, "checkForMovieResume: " + currentMovieFilename );
			final int seekPos = getPreferences( MODE_PRIVATE ).getInt( currentMovieFilename + "_pos", 0 );
			Log.v( TAG, "seekPos = " + seekPos );
			Log.v( TAG, "resumePlayback = " + resumePlayback );
*/
            try
            {
/*
				if ( resumePlayback && ( seekPos > 0 ) )
				{
					Log.v( TAG, "resuming at saved location" );
					mediaPlayer.seekTo( seekPos );
				}
				else
				{
					// start at beginning
					Log.v( TAG, "start at beginning" );
					mediaPlayer.seekTo( 0 );
				}
*/
            }

            catch ( IllegalStateException t )
            {
                Fail( "DemoPlayer.seekTo threw illegalStateException" );
                return;
            }

/*
// need to replace this for ExoPlayer

			mediaPlayer.setLooping( loop );
			mediaPlayer.setOnCompletionListener( new OnCompletionListener()
			{
	        	public void onCompletion( MediaPlayer mp )
	        	{
	        		Log.v(TAG, "mediaPlayer.onCompletion" );
	        		playbackFinished = true;
	        		saveCurrentMovieLocation();
	        		releaseAudioFocus();
	        	}
	        });

			mediaPlayer.setOnSeekCompleteListener( new OnSeekCompleteListener()
			{
	        	public void onSeekComplete( MediaPlayer mp )
	        	{
	        		if ( haveSeekWaiting )
	        		{
	        			mediaPlayer.seekTo( nextSeekPosition );
	        			haveSeekWaiting = false;
	        		}
	        		else
	        		{
	        			waitingForSeek = false;
	        		}
	        	}
	        });
*/
            try
            {
                player.setPlayWhenReady(true);
            }

            catch ( IllegalStateException t )
            {
                Fail( "SimpleExoPlayer.setPlayWhenReady threw illegalStateException" );
            }
/*
			// Save the current movie now that it was successfully started
			Editor edit = getPreferences( MODE_PRIVATE ).edit();
			edit.putString( "currentMovie", currentMovieFilename );
			edit.commit();
*/
        }

        Log.v( TAG, "exiting startMovie" );
    }

    public void pause() { pauseMovie(); }
    public void pauseMovie()
    {
        Log.d( TAG, "pauseMovie()" );
        if ( player != null )
        {
            if ( isPlaying() )
            {
//				saveCurrentMovieLocation();
            }

            try
            {
                player.pause();
            }

            catch ( IllegalStateException t )
            {
                Log.e(TAG, "pauseMovie() caught illegalStateException" );
            }
        }
    }

    public void start() { resumeMovie(); }
    public void resumeMovie()
    {
        Log.d(TAG, "resumeMovie()" );
        if ( player != null )
        {
            try
            {
                player.start();
            }

            catch ( IllegalStateException t )
            {
                Log.e( TAG, "resumeMovie() caught illegalStateException" );
            }
        }
    }

    public void stopMovie()
    {
        Log.v( TAG, "stopMovie" );

        synchronized (this)
        {
            if ( player != null )
            {
                // don't save location if not playing
                if ( isPlaying() )
                {
//					saveCurrentMovieLocation();
                }

                player.release();
            }
            releaseAudioFocus();

            playbackFailed = false;
            playbackFinished = true;
        }
    }

    public boolean togglePlaying()
    {
        boolean result = false;

        Log.d( TAG,  "MainActivity.togglePlaying()" );
        if ( player != null )
        {
            if ( isPlaying() )
            {
                pauseMovie();
                result = false;
            }
            else
            {
                resumeMovie();
                result = true;
            }
        }
        else
        {
            Log.d( TAG, "mediaPlayer == null" );
        }

        return result;
    }

    private void Fail( final String message )
    {
        Log.e(TAG, message );
        player.release();
        playbackFinished = true;
        playbackFailed = true;
        releaseAudioFocus();
    }

    private void releaseAudioFocus()
    {
        throw new RuntimeException("Stub!");
    }

    /*public void enableQuadStereo()
    {
        Log.d( TAG, "enableQuadStereo()");
        mediaPlayer.setSelectedAudioTrack(0, 0);
        mediaPlayer.setSelectedAudioTrack(1, 1);
        mediaPlayer.setSelectedAudioTrack(2, 2);
        mediaPlayer.setSelectedAudioTrack(3, 3);
    }

    public void setQuadStereoVolumes(float v000, float v090, float v180, float v270)
    {
        Log.d( TAG, "setQuadStereoVolumes(): getSelectedAudioTrack"
                + " (0) " + mediaPlayer.getSelectedAudioTrack(0)
                + " (1) " + mediaPlayer.getSelectedAudioTrack(1)
                + " (2) " + mediaPlayer.getSelectedAudioTrack(2)
                + " (3) " + mediaPlayer.getSelectedAudioTrack(3)
        );
        mediaPlayer.setAudioTrackVolume(0, v000);
        mediaPlayer.setAudioTrackVolume(1, v090);
        mediaPlayer.setAudioTrackVolume(2, v180);
        mediaPlayer.setAudioTrackVolume(3, v270);
    }*/

    // Renderer building.

    private void buildRenderers(Context context, Handler mainHandler,
                                DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                                @ExtensionRendererMode int extensionRendererMode, long allowedVideoJoiningTimeMs,
                                ArrayList<Renderer> out) {
        buildVideoRenderers(context, mainHandler, drmSessionManager, extensionRendererMode,
                componentListener, allowedVideoJoiningTimeMs, out);
        buildAudioRenderers(context, mainHandler, drmSessionManager, extensionRendererMode,
                componentListener, buildAudioProcessors(), out);
        buildTextRenderers(context, mainHandler, extensionRendererMode, componentListener, out);
        buildMetadataRenderers(context, mainHandler, extensionRendererMode, componentListener, out);
        buildMiscellaneousRenderers(context, mainHandler, extensionRendererMode, out);
    }

    /**
     * Builds video renderers for use by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param mainHandler A handler associated with the main thread's looper.
     * @param drmSessionManager An optional {@link DrmSessionManager}. May be null if the player will
     *     not be used for DRM protected playbacks.
     * @param extensionRendererMode The extension renderer mode.
     * @param eventListener An event listener.
     * @param allowedVideoJoiningTimeMs The maximum duration in milliseconds for which video renderers
     *     can attempt to seamlessly join an ongoing playback.
     * @param out An array to which the built renderers should be appended.
     */
    protected void buildVideoRenderers(Context context, Handler mainHandler,
                                       DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                                       @ExtensionRendererMode int extensionRendererMode, VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs, ArrayList<Renderer> out) {
        out.add(new MediaCodecVideoRenderer(context, MediaCodecSelector.DEFAULT,
                allowedVideoJoiningTimeMs, drmSessionManager, false, mainHandler, eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY));

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return;
        }
        int extensionRendererIndex = out.size();
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--;
        }

        try {
            Class<?> clazz =
                    Class.forName("com.google.android.exoplayer2.ext.vp9.LibvpxVideoRenderer");
            Constructor<?> constructor = clazz.getConstructor(boolean.class, long.class, Handler.class,
                    VideoRendererEventListener.class, int.class);
            Renderer renderer = (Renderer) constructor.newInstance(true, allowedVideoJoiningTimeMs,
                    mainHandler, componentListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded LibvpxVideoRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds audio renderers for use by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param mainHandler A handler associated with the main thread's looper.
     * @param drmSessionManager An optional {@link DrmSessionManager}. May be null if the player will
     *     not be used for DRM protected playbacks.
     * @param extensionRendererMode The extension renderer mode.
     * @param eventListener An event listener.
     * @param audioProcessors An array of {@link AudioProcessor}s that will process PCM audio buffers
     *     before output. May be empty.
     * @param out An array to which the built renderers should be appended.
     */
    protected void buildAudioRenderers(Context context, Handler mainHandler,
                                       DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                                       @ExtensionRendererMode int extensionRendererMode, AudioRendererEventListener eventListener,
                                       AudioProcessor[] audioProcessors, ArrayList<Renderer> out) {
        out.add(new MediaCodecAudioRenderer(MediaCodecSelector.DEFAULT, drmSessionManager, true,
                mainHandler, eventListener, AudioCapabilities.getCapabilities(context), audioProcessors));

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return;
        }
        int extensionRendererIndex = out.size();
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--;
        }

        try {
            Class<?> clazz =
                    Class.forName("com.google.android.exoplayer2.ext.opus.LibopusAudioRenderer");
            Constructor<?> constructor = clazz.getConstructor(Handler.class,
                    AudioRendererEventListener.class, AudioProcessor[].class);
            Renderer renderer = (Renderer) constructor.newInstance(mainHandler, componentListener,
                    audioProcessors);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded LibopusAudioRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            Class<?> clazz =
                    Class.forName("com.google.android.exoplayer2.ext.flac.LibflacAudioRenderer");
            Constructor<?> constructor = clazz.getConstructor(Handler.class,
                    AudioRendererEventListener.class, AudioProcessor[].class);
            Renderer renderer = (Renderer) constructor.newInstance(mainHandler, componentListener,
                    audioProcessors);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded LibflacAudioRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            Class<?> clazz =
                    Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegAudioRenderer");
            Constructor<?> constructor = clazz.getConstructor(Handler.class,
                    AudioRendererEventListener.class, AudioProcessor[].class);
            Renderer renderer = (Renderer) constructor.newInstance(mainHandler, componentListener,
                    audioProcessors);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded FfmpegAudioRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Builds text renderers for use by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param mainHandler A handler associated with the main thread's looper.
     * @param extensionRendererMode The extension renderer mode.
     * @param output An output for the renderers.
     * @param out An array to which the built renderers should be appended.
     */
    protected void buildTextRenderers(Context context, Handler mainHandler,
                                      @ExtensionRendererMode int extensionRendererMode, TextRenderer.Output output,
                                      ArrayList<Renderer> out) {
        out.add(new TextRenderer(output, mainHandler.getLooper()));
    }

    /**
     * Builds metadata renderers for use by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param mainHandler A handler associated with the main thread's looper.
     * @param extensionRendererMode The extension renderer mode.
     * @param output An output for the renderers.
     * @param out An array to which the built renderers should be appended.
     */
    protected void buildMetadataRenderers(Context context, Handler mainHandler,
                                          @ExtensionRendererMode int extensionRendererMode, MetadataRenderer.Output output,
                                          ArrayList<Renderer> out) {
        out.add(new MetadataRenderer(output, mainHandler.getLooper()));
    }

    /**
     * Builds any miscellaneous renderers used by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param mainHandler A handler associated with the main thread's looper.
     * @param extensionRendererMode The extension renderer mode.
     * @param out An array to which the built renderers should be appended.
     */
    protected void buildMiscellaneousRenderers(Context context, Handler mainHandler,
                                               @ExtensionRendererMode int extensionRendererMode, ArrayList<Renderer> out) {
        // Do nothing.
    }

    /**
     * Builds an array of {@link AudioProcessor}s that will process PCM audio before output.
     */
    protected AudioProcessor[] buildAudioProcessors() {
        return new AudioProcessor[0];
    }

    // Internal methods.

    private void removeSurfaceCallbacks() {
        if (textureView != null) {
            if (textureView.getSurfaceTextureListener() != componentListener) {
                Log.w(TAG, "SurfaceTextureListener already unset or replaced.");
            } else {
                textureView.setSurfaceTextureListener(null);
            }
            textureView = null;
        }
        if (surfaceHolder != null) {
            surfaceHolder.removeCallback(componentListener);
            surfaceHolder = null;
        }
    }

    private void setVideoSurfaceInternal(Surface surface, boolean ownsSurface) {
        // Note: We don't turn this method into a no-op if the surface is being replaced with itself
        // so as to ensure onRenderedFirstFrame callbacks are still called in this case.
        ExoPlayerMessage[] messages = new ExoPlayerMessage[videoRendererCount];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_VIDEO) {
                messages[count++] = new ExoPlayerMessage(renderer, C.MSG_SET_SURFACE, surface);
            }
        }
        if (this.surface != null && this.surface != surface) {
            // If we created this surface, we are responsible for releasing it.
            if (this.ownsSurface) {
                this.surface.release();
            }
            // We're replacing a surface. Block to ensure that it's not accessed after the method returns.
            player.blockingSendMessages(messages);
        } else {
            player.sendMessages(messages);
        }
        this.surface = surface;
        this.ownsSurface = ownsSurface;
    }

    private final class ComponentListener implements VideoRendererEventListener,
            AudioRendererEventListener, TextRenderer.Output, MetadataRenderer.Output,
            SurfaceHolder.Callback, TextureView.SurfaceTextureListener {

        // VideoRendererEventListener implementation

        @Override
        public void onVideoEnabled(DecoderCounters counters) {
            videoDecoderCounters = counters;
            if (videoDebugListener != null) {
                videoDebugListener.onVideoEnabled(counters);
            }
        }

        @Override
        public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs,
                                              long initializationDurationMs) {
            if (videoDebugListener != null) {
                videoDebugListener.onVideoDecoderInitialized(decoderName, initializedTimestampMs,
                        initializationDurationMs);
            }
        }

        @Override
        public void onVideoInputFormatChanged(Format format) {
            videoFormat = format;
            if (videoDebugListener != null) {
                videoDebugListener.onVideoInputFormatChanged(format);
            }
        }

        @Override
        public void onDroppedFrames(int count, long elapsed) {
            if (videoDebugListener != null) {
                videoDebugListener.onDroppedFrames(count, elapsed);
            }
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                       float pixelWidthHeightRatio) {
            if (videoListener != null) {
                videoListener.onVideoSizeChanged(width, height, unappliedRotationDegrees,
                        pixelWidthHeightRatio);
            }
            if (videoDebugListener != null) {
                videoDebugListener.onVideoSizeChanged(width, height, unappliedRotationDegrees,
                        pixelWidthHeightRatio);
            }
        }

        @Override
        public void onRenderedFirstFrame(Surface surface) {
            if (videoListener != null && SimpleExoPlayerUpdate.this.surface == surface) {
                videoListener.onRenderedFirstFrame();
            }
            if (videoDebugListener != null) {
                videoDebugListener.onRenderedFirstFrame(surface);
            }
        }

        @Override
        public void onVideoDisabled(DecoderCounters counters) {
            if (videoDebugListener != null) {
                videoDebugListener.onVideoDisabled(counters);
            }
            videoFormat = null;
            videoDecoderCounters = null;
        }

        // AudioRendererEventListener implementation

        @Override
        public void onAudioEnabled(DecoderCounters counters) {
            audioDecoderCounters = counters;
            if (audioDebugListener != null) {
                audioDebugListener.onAudioEnabled(counters);
            }
        }

        @Override
        public void onAudioSessionId(int sessionId) {
            audioSessionId = sessionId;
            if (audioDebugListener != null) {
                audioDebugListener.onAudioSessionId(sessionId);
            }
        }

        @Override
        public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs,
                                              long initializationDurationMs) {
            if (audioDebugListener != null) {
                audioDebugListener.onAudioDecoderInitialized(decoderName, initializedTimestampMs,
                        initializationDurationMs);
            }
        }

        @Override
        public void onAudioInputFormatChanged(Format format) {
            audioFormat = format;
            if (audioDebugListener != null) {
                audioDebugListener.onAudioInputFormatChanged(format);
            }
        }

        @Override
        public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs,
                                         long elapsedSinceLastFeedMs) {
            if (audioDebugListener != null) {
                audioDebugListener.onAudioTrackUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
            }
        }

        @Override
        public void onAudioDisabled(DecoderCounters counters) {
            if (audioDebugListener != null) {
                audioDebugListener.onAudioDisabled(counters);
            }
            audioFormat = null;
            audioDecoderCounters = null;
            audioSessionId = C.AUDIO_SESSION_ID_UNSET;
        }

        // TextRenderer.Output implementation

        @Override
        public void onCues(List<Cue> cues) {
            if (textOutput != null) {
                textOutput.onCues(cues);
            }
        }

        // MetadataRenderer.Output implementation

        @Override
        public void onMetadata(Metadata metadata) {
            if (metadataOutput != null) {
                metadataOutput.onMetadata(metadata);
            }
        }

        // SurfaceHolder.Callback implementation

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            setVideoSurfaceInternal(holder.getSurface(), false);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // Do nothing.
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            setVideoSurfaceInternal(null, false);
        }

        // TextureView.SurfaceTextureListener implementation

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            setVideoSurfaceInternal(new Surface(surfaceTexture), true);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            // Do nothing.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            setVideoSurfaceInternal(null, true);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // Do nothing.
        }

    }

    @TargetApi(23)
    private static final class PlaybackParamsHolder {

        public final PlaybackParams params;

        public PlaybackParamsHolder(PlaybackParams params) {
            this.params = params;
        }

    }

}
