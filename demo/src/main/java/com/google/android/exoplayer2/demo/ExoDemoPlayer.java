package com.google.android.exoplayer2.demo;

import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer2.metadata.MetadataRenderer;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.DebugTextViewHelper;
import com.google.android.exoplayer2.ui.PlaybackControlView;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public class ExoDemoPlayer implements ExoPlayer.EventListener,
    AudioRendererEventListener, VideoRendererEventListener, AdaptiveMediaSourceEventListener,
    ExtractorMediaSource.EventListener, DefaultDrmSessionManager.EventListener,
    MetadataRenderer.Output
{
    public static final String TAG = "ExoDemoPlayer";

    String 				currentMovieFilename;

    boolean				playbackFinished = true;
    boolean				playbackFailed = false;

    private boolean 	waitingForSeek = false;
    private boolean 	haveSeekWaiting = false;
    private int 		nextSeekPosition = 0;
    private long       	startTime = 0;

    private int			textureId = -1;
    SurfaceTexture movieTexture = null;
    Surface movieSurface = null;

    SimpleExoPlayer	    mediaPlayer = null;
    AudioManager 		audioManager = null;

    Context context = null;

    public static Application getApplicationUsingReflection() throws Exception {
        return (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null, (Object[]) null);
    }

    public ExoDemoPlayer(/*Context c, int texId, String s*/) {
        try { context = getApplicationUsingReflection().getApplicationContext(); } catch (Exception e) {}

        EventLogger.setDefaultCookieManager();
        audioManager = ( AudioManager )context.getSystemService( Context.AUDIO_SERVICE );
/*
		textureId = texId;
		if (s != null)
			startMovieLocal(s);
*/
    }

    public DemoPlayer getDemoPlayer() { return mediaPlayer; }

    public void setTextureId(int texId) {
        textureId = texId;
    }

    public void setSurface(Surface s) {
        movieSurface = s;
    }

    private void Fail( final String message )
    {
        Log.e(TAG, message );
        mediaPlayer.release();
        mediaPlayer = null;
        playbackFinished = true;
        playbackFailed = true;
        releaseAudioFocus();
    }

    public void onAudioFocusChange( int focusChange )
    {
        switch( focusChange )
        {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume() if coming back from transient loss, raise stream volume if duck applied
                Log.d( TAG, "onAudioFocusChangedListener: AUDIOFOCUS_GAIN" );
                break;
            case AudioManager.AUDIOFOCUS_LOSS:				// focus lost permanently
                // stop() if isPlaying
                Log.d( TAG, "onAudioFocusChangedListener: AUDIOFOCUS_LOSS" );
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:	// focus lost temporarily
                // pause() if isPlaying
                Log.d( TAG, "onAudioFocusChangedListener: AUDIOFOCUS_LOSS_TRANSIENT" );
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:	// focus lost temporarily
                // lower stream volume
                Log.d( TAG, "onAudioFocusChangedListener: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK" );
                break;
            default:
                break;
        }
    }

    // this is for DemoPlayer
    @Override
    //public void onVideoSizeChanged( int width, int height, float something )
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                   float pixelWidthAspectRatio)
    {
        Log.v( TAG, String.format( "onVideoSizeChanged: %dx%d %d %f", width, height, unappliedRotationDegrees, pixelWidthAspectRatio) );
/*
		int rotation = getRotationFromMetadata( currentMovieFilename );
		int duration = getDuration();
		nativeSetVideoSize( appPtr, width, height, rotation, duration );
*/
    }

    // this is for DemoPlayer
    @Override
    public void onError(Exception e) {
        // TODO
        if (e instanceof UnsupportedDrmException) {
            // Special case DRM failures.
            UnsupportedDrmException unsupportedDrmException = (UnsupportedDrmException) e;

            String stringId = unsupportedDrmException.reason == UnsupportedDrmException.REASON_INSTANTIATION_ERROR
                    ? "drm_error_not_supported"
                    : unsupportedDrmException.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                    ? "drm_error_unsupported_scheme"
                    : "drm_error_unknown";
            //createVrToast(stringId);
            Log.e(TAG, String.format("DemoPlayer.onError DRM %s, %s", e.getMessage(), stringId ));
        } else {
            //createVrToast(e.getMessage());
            Log.e(TAG, String.format("DemoPlayer.onError %s", e.getMessage()));
        }

        // TODO... playerNeedsPrepare = true;
    }

    // this is for DemoPlayer
    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        if (playbackState == ExoPlayer.STATE_ENDED) {
            // TODO... showControls();
            Log.v(TAG, "DemoPlayer.onStateChanged playbackState == STATE_ENDED" );
/*
    		playbackFinished = true;
    		saveCurrentMovieLocation();
    		releaseAudioFocus();
*/
        }
        String text = "playWhenReady=" + playWhenReady + ", playbackState=";
        switch(playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                text += "buffering";
                break;
            case ExoPlayer.STATE_ENDED:
                text += "ended";
                break;
            case ExoPlayer.STATE_IDLE:
                text += "idle";
                break;
            case ExoPlayer.STATE_PREPARING:
                text += "preparing";
                break;
            case ExoPlayer.STATE_READY:
                text += "ready";
                break;
            default:
                text += "unknown";
                break;
        }
        // TODO... playerStateTextView.setText(text);
        // works but can mask other toast like DRM error... createVrToast(text);
        Log.i(TAG, String.format("DemoPlayer.onStateChanged %s", text));
        // TODO... updateButtonVisibilities();
    }

    // this is for DemoPlayer
    @Override
    public void onCues(List<Cue> cues) {
        Log.i(TAG, String.format("DemoPlayer.onCues %s", cues.toString()));
    }
/*
	@Override
	public void onText(String text) {
	    if (TextUtils.isEmpty(text)) {
	      // TODO... subtitleView.setVisibility(View.INVISIBLE);
	    } else {
	    	// TODO... subtitleView.setVisibility(View.VISIBLE);
	    	// TODO... subtitleView.setText(text);
	        //createVrToast(text);
	        Log.i(TAG, String.format("DemoPlayer.onText %s", text));
	    }
	}
*/
    // DemoPlayer.MetadataListener implementation

    @Override
    public void onId3Metadata(List<Id3Frame> id3Frames) {
        for (int i = 0; i < id3Frames.size(); i++) {
            if (id3Frames.get(i).id == TxxxFrame.ID) {
                TxxxFrame txxx = (TxxxFrame) id3Frames.get(i);
                Log.i(TAG, String.format("ID3 TimedMetadata: description=%s, value=%s",
                        txxx.description, txxx.value));
            }
        }
    }

    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;
    private static AudioCapabilities audioCapabilities;

    // AudioCapabilitiesReceiver.Listener methods

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        boolean audioCapabilitiesChanged = !audioCapabilities.equals(this.audioCapabilities);
        if ( //player == null ||
                audioCapabilitiesChanged) {
            this.audioCapabilities = audioCapabilities;
	/* FIXME...
	      releasePlayer();
	      preparePlayer();
	    } else if (player != null) {
	      player.setBackgrounded(false);
	*/
        }
    }


    // this is for DemoPlayer
    private static RendererBuilder getRendererBuilder(Context context, String contentId, String provider, Uri contentUri, int contentType) {
        String userAgent = Util.getUserAgent(context, "ExoPlayerDemo");
        TextView debugTextView = null;
        switch (contentType) {
            case DemoUtil.TYPE_SS:
                return new SmoothStreamingRendererBuilder(context, userAgent, contentUri.toString(),
                        new SmoothStreamingTestMediaDrmCallback());
            case DemoUtil.TYPE_DASH:
                return new DashRendererBuilder(context, userAgent, contentUri.toString(),
                        new WidevineTestMediaDrmCallback(contentId, provider), true); // force L3
            case DemoUtil.TYPE_HLS:
                return new HlsRendererBuilder(context, userAgent, contentUri.toString());
            case DemoUtil.TYPE_M4A: // There are no file format differences between M4A and MP4.
            case DemoUtil.TYPE_MP4:
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
            case DemoUtil.TYPE_MP3:
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
            case DemoUtil.TYPE_TS:
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
            case DemoUtil.TYPE_AAC:
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
            case DemoUtil.TYPE_WEBM:
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
            default: // other
                //throw new IllegalStateException("Unsupported type: " + contentType);
                return new ExtractorRendererBuilder(context, userAgent, contentUri);
        }
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


    public boolean isPlaying()
    {
        if ( mediaPlayer != null )
        {
            try
            {
                return mediaPlayer.getPlayerControl().isPlaying();
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

    public int getCurrentPosition() { return getPosition(); }

    //EJ - Added these getters 2/14/2017
    public long getBandwidth()
    {
        if ( mediaPlayer != null )
        {
            try
            {
                return mediaPlayer.getBandwidthMeter().getBitrateEstimate();
            }
            catch( IllegalStateException ise )
            {
                // Can be thrown by the media player if state changes while we're being
                // queued for execution on the main thread
                Log.d( TAG, "getBandwidth(): Caught illegalStateException" );
                return 0;
            }
        }
        return 0;
    }

    public String getStateString(){
        String text = "";
        switch(mediaPlayer.getPlaybackState()) {
            case ExoPlayer.STATE_BUFFERING:
                text += "buffering";
                break;
            case ExoPlayer.STATE_ENDED:
                text += "ended";
                break;
            case ExoPlayer.STATE_IDLE:
                text += "idle";
                break;
            case ExoPlayer.STATE_PREPARING:
                text += "preparing";
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
    public long getBufferedPercentage()
    {
        if ( mediaPlayer != null )
        {
            try
            {
                return mediaPlayer.getBufferedPercentage();
            }
            catch( IllegalStateException ise )
            {
                // Can be thrown by the media player if state changes while we're being
                // queued for execution on the main thread
                Log.d( TAG, "getBufferedPercentage(): Caught illegalStateException" );
                return 0;
            }
        }
        return 0;
    }

    //End EJ Additions

    public int getPosition()
    {
        if ( mediaPlayer != null )
        {
            try
            {
                return mediaPlayer.getPlayerControl().getCurrentPosition();
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

    public int getDuration()
    {
        if ( mediaPlayer != null )
        {
            try
            {
                return mediaPlayer.getPlayerControl().getDuration();
            }
            catch( IllegalStateException ise )
            {
                // Can be thrown by the media player if state changes while we're being
                // queued for execution on the main thread
                Log.d( TAG, "getDuration(): Caught illegalStateException" );
                return 0;
            }
        }
        return 0;
    }

    public void seekTo( int positionMilliseconds ) { setPosition(positionMilliseconds); }

    public void setPosition( int positionMilliseconds )
    {
        try
        {
            if ( mediaPlayer != null )
            {
                boolean wasPlaying = isPlaying();
                if ( wasPlaying )
                {
                    mediaPlayer.getPlayerControl().pause();
                }
                int duration = mediaPlayer.getPlayerControl().getDuration();
                int newPosition = positionMilliseconds;
                if ( newPosition >= duration )
                {
                    // pause if seeking past end
                    Log.d( TAG, "seek past end" );
                    mediaPlayer.seekTo( duration );
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
                    mediaPlayer.seekTo( newPosition );
                }

                if ( wasPlaying )
                {
                    mediaPlayer.getPlayerControl().start();
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

    public void enableQuadStereo()
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
    }

    public void seekDelta( int deltaMilliseconds )
    {
        try
        {
            if ( mediaPlayer != null )
            {
                boolean wasPlaying = isPlaying();
                if ( wasPlaying )
                {
                    mediaPlayer.getPlayerControl().pause();
                }

                int position = mediaPlayer.getPlayerControl().getCurrentPosition();
                int duration = mediaPlayer.getPlayerControl().getDuration();
                int newPosition = position + deltaMilliseconds;
                if ( newPosition >= duration )
                {
                    // pause if seeking past end
                    Log.d( TAG, "seek past end" );
                    mediaPlayer.seekTo( duration );
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
                    mediaPlayer.seekTo( newPosition );
                }

                if ( wasPlaying )
                {
                    mediaPlayer.getPlayerControl().start();
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

    private void requestAudioFocus()
    {
        // Request audio focus
        int result = audioManager.requestAudioFocus( this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN );
        if ( result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED )
        {
            Log.d(TAG,"startMovie(): GRANTED audio focus");
        }
        else if ( result == AudioManager.AUDIOFOCUS_REQUEST_FAILED )
        {
            Log.d(TAG,"startMovie(): FAILED to gain audio focus");
        }
    }

    private void releaseAudioFocus()
    {
        audioManager.abandonAudioFocus( this );
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
        if (textureId != -1 && movieSurface == null) {
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
            movieSurface = new Surface( movieTexture );
        }

        final ExoDemoPlayer self = this;

        synchronized( this )
        {
            requestAudioFocus();

            playbackFinished = false;
            playbackFailed = false;

            waitingForSeek = false;
            haveSeekWaiting = false;
            nextSeekPosition = 0;

            currentMovieFilename = pathName;

            if (mediaPlayer != null)
            {
                mediaPlayer.release();
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
                            mediaPlayer = new DemoPlayer(getRendererBuilder(
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
                            mediaPlayer.addListener(self);
                            // TODO... InfoListener
                            mediaPlayer.setCaptionListener(self);
                            mediaPlayer.setMetadataListener(self);

                            try
                            {
                                Log.v(TAG, "DemoPlayer.prepare");
                                mediaPlayer.prepare();
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
            mediaPlayer.setSurface(movieSurface);

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
                mediaPlayer.setPlayWhenReady(true);
            }

            catch ( IllegalStateException t )
            {
                Fail( "DemoPlayer.setPlayWhenReady threw illegalStateException" );
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
        if ( mediaPlayer != null )
        {
            if ( isPlaying() )
            {
//				saveCurrentMovieLocation();
            }

            try
            {
                mediaPlayer.getPlayerControl().pause();
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
        if ( mediaPlayer != null )
        {
            try
            {
                mediaPlayer.getPlayerControl().start();
            }

            catch ( IllegalStateException t )
            {
                Log.e( TAG, "resumeMovie() caught illegalStateException" );
            }
        }
    }

    public void stop() { stopMovie(); }
    public void release() { stopMovie(); }
    public void stopMovie()
    {
        Log.v( TAG, "stopMovie" );

        synchronized (this)
        {
            if ( mediaPlayer != null )
            {
                // don't save location if not playing
                if ( isPlaying() )
                {
//					saveCurrentMovieLocation();
                }

                mediaPlayer.release();
                mediaPlayer = null;
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
        if ( mediaPlayer != null )
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
}
