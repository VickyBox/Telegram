package org.telegram.messenger.video;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.view.SurfaceView;
import android.view.TextureView;

import com.google.android.exoplayer2.ExoPlayer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

//used for player in background thread
public class VideoPlayerHolderBase {

    public boolean paused;
    public TLRPC.Document document;
    VideoPlayer videoPlayer;
    Runnable initRunnable;
    volatile boolean released;
    public boolean firstFrameRendered;

    public float progress;
    int lastState;
    public long currentPosition;
    private int currentAccount;
    long playerDuration;
    boolean audioDisabled;
    public boolean stubAvailable;

    private TextureView textureView;
    private SurfaceView surfaceView;
    public Bitmap playerStubBitmap;
    public Paint playerStubPaint;
    public long pendingSeekTo;
    Uri contentUri;

    public VideoPlayerHolderBase() {

    }

    public VideoPlayerHolderBase with(SurfaceView surfaceView) {
        this.surfaceView = surfaceView;
        this.textureView = null;
        return this;
    }

    public VideoPlayerHolderBase with(TextureView textureView) {
        this.surfaceView = null;
        this.textureView = textureView;
        return this;
    }


    final DispatchQueue dispatchQueue = Utilities.getOrCreatePlayerQueue();
    public Uri uri;

    Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoPlayer != null) {
                if (lastState == ExoPlayer.STATE_ENDED) {
                    progress = 1f;
                } else {
                    currentPosition = videoPlayer.getCurrentPosition();
                    playerDuration = videoPlayer.getDuration();
                }
                if (lastState == ExoPlayer.STATE_READY) {
                    dispatchQueue.cancelRunnable(progressRunnable);
                    dispatchQueue.postRunnable(progressRunnable, 16);
                }
            }
        }
    };

    long startTime;

    public void preparePlayer(Uri uri, boolean audioDisabled) {
        this.audioDisabled = audioDisabled;
        this.currentAccount = currentAccount;
        this.contentUri = uri;
        paused = true;
        if (initRunnable != null) {
            dispatchQueue.cancelRunnable(initRunnable);
        }
        dispatchQueue.postRunnable(initRunnable = () -> {
            if (released) {
                return;
            }
            ensurePlayerCreated(audioDisabled);
            videoPlayer.preparePlayer(uri, "other", FileLoader.PRIORITY_LOW);
            videoPlayer.setPlayWhenReady(false);
            videoPlayer.setWorkerQueue(dispatchQueue);
        });
    }

    public void start(boolean paused, Uri uri, long position, boolean audioDisabled) {
        startTime = System.currentTimeMillis();
        this.audioDisabled = audioDisabled;
        this.paused = paused;
        if (position > 0) {
            currentPosition = position;
        }
        dispatchQueue.postRunnable(initRunnable = () -> {
            if (released) {
                return;
            }
            if (videoPlayer == null) {
                ensurePlayerCreated(audioDisabled);
                videoPlayer.preparePlayer(uri, "other");
                videoPlayer.setWorkerQueue(dispatchQueue);
                if (!paused) {
                    if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.setPlayWhenReady(true);
                }
            } else {
                if (!paused) {
                    if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.play();
                }
            }
            if (position > 0) {
                videoPlayer.seekTo(position);
            }

           // videoPlayer.setVolume(isInSilentMode ? 0 : 1f);
            AndroidUtilities.runOnUIThread(() -> initRunnable = null);
        });
    }

    private void ensurePlayerCreated(boolean audioDisabled) {
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
        }
        videoPlayer = new VideoPlayer(false, audioDisabled);
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                lastState = playbackState;
                if (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_BUFFERING) {
                    dispatchQueue.cancelRunnable(progressRunnable);
                    dispatchQueue.postRunnable(progressRunnable);
                } else if (playbackState == ExoPlayer.STATE_ENDED) {
                    if (needRepeat()) {
                        progress = 0;
                        videoPlayer.seekTo(0);
                        videoPlayer.play();
                    } else {
                        progress = 1f;
                    }
                }
                VideoPlayerHolderBase.this.onStateChanged(playWhenReady, playbackState);
            }

            @Override
            public void onError(VideoPlayer player, Exception e) {
                FileLog.e(e);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

            }

            @Override
            public void onRenderedFirstFrame() {
                AndroidUtilities.runOnUIThread(() -> {
                    if (released ) {
                        return;
                    }
                    VideoPlayerHolderBase.this.onRenderedFirstFrame();

                    if (onReadyListener != null) {
                        onReadyListener.run();
                        onReadyListener = null;
                    }
                }, surfaceView == null ? 16 : 32);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }
        });
        videoPlayer.setIsStory();
    }


    private Runnable onReadyListener;
    public void setOnReadyListener(Runnable listener) {
        onReadyListener = listener;
    }

    public boolean release(Runnable whenReleased) {
        TLRPC.Document document = this.document;
        if (document != null) {
            int priority = FileStreamLoadOperation.getStreamPrioriy(document);
            if (priority != FileLoader.PRIORITY_LOW) {
                FileStreamLoadOperation.setPriorityForDocument(document, FileLoader.PRIORITY_LOW);
                FileLoader.getInstance(currentAccount).changePriority(FileLoader.PRIORITY_LOW, document, null, null, null, null, null);
            }
        }
        released = true;
        dispatchQueue.cancelRunnable(initRunnable);
        initRunnable = null;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.setTextureView(null);
                videoPlayer.setSurfaceView(null);
                videoPlayer.releasePlayer(false);
            }
            if (document != null) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
            }
            if (whenReleased != null) {
                AndroidUtilities.runOnUIThread(whenReleased);
            }
            videoPlayer = null;
        });
        if (playerStubBitmap != null) {
            AndroidUtilities.recycleBitmap(playerStubBitmap);
            playerStubBitmap = null;
        }
        return true;
    }

    public void pause() {
        if (released) {
            return;
        }
        if (paused) {
            return;
        }
        paused = true;
        prepareStub();
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        });
    }

    public void prepareStub() {
        if (surfaceView != null && firstFrameRendered && surfaceView.getHolder().getSurface().isValid()) {
            stubAvailable = true;
            if (playerStubBitmap == null) {
                playerStubBitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
                playerStubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AndroidUtilities.getBitmapFromSurface(surfaceView, playerStubBitmap);
                if (playerStubBitmap.getPixel(0, 0) == Color.TRANSPARENT) {
                    stubAvailable = false;
                }
            }
        }
    }

    public void play() {
        if (released) {
            return;
        }
        if (!paused) {
            return;
        }
        paused = false;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                if (surfaceView != null) {
                    videoPlayer.setSurfaceView(surfaceView);
                } else {
                    videoPlayer.setTextureView(textureView);
                }
                if (pendingSeekTo > 0) {
                    videoPlayer.seekTo(pendingSeekTo);
                    pendingSeekTo = 0;
                }
                videoPlayer.setPlayWhenReady(true);
            }
        });
    }

    public void setAudioEnabled(boolean enabled, boolean prepared) {
        boolean disabled = !enabled;
        if (audioDisabled == disabled) {
            return;
        }
        audioDisabled = disabled;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                return;
            }
            boolean playing = videoPlayer.isPlaying();
            if (enabled && !videoPlayer.createdWithAudioTrack()) {
                //release and create new with audio track
                videoPlayer.pause();
                long position = videoPlayer.getCurrentPosition();
                videoPlayer.releasePlayer(false);
                videoPlayer = null;
                ensurePlayerCreated(audioDisabled);
                videoPlayer.preparePlayer(uri, "other");
                videoPlayer.setWorkerQueue(dispatchQueue);
                if (!prepared) {
                    if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else  {
                        videoPlayer.setTextureView(textureView);
                    }
                }
                //    videoPlayer.setTextureView(textureView);
                videoPlayer.seekTo(position + 50);
                if (playing && !prepared) {
                    videoPlayer.setPlayWhenReady(true);
                    videoPlayer.play();
                } else {
                    videoPlayer.setPlayWhenReady(false);
                    videoPlayer.pause();
                }
            } else {
                videoPlayer.setVolume(enabled ? 1f : 0);
            }
        });
    }

    public float getPlaybackProgress(long totalDuration) {
        if (lastState == ExoPlayer.STATE_ENDED) {
            progress = 1f;
        } else {
            float localProgress;
            if (totalDuration != 0) {
                localProgress = currentPosition / (float) totalDuration;
            } else {
                localProgress = currentPosition / (float) playerDuration;
            }
            if (localProgress < progress) {
                return progress;
            }
            progress = localProgress;
        }
        return progress;
    }

    public void loopBack() {
        progress = 0;
        lastState = ExoPlayer.STATE_IDLE;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.seekTo(0);
            }
            progress = 0;
            currentPosition = 0;
        });
    }

    public void setVolume(float v) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.setVolume(v);
            }
        });
    }

    public boolean isBuffering() {
        return !released && lastState == ExoPlayer.STATE_BUFFERING;
    }

    public long getCurrentPosition() {
        return currentPosition;
    }

    public long getDuration() {
        return playerDuration;
    }

    public boolean isPlaying() {
        return !paused;
    }

    public void onRenderedFirstFrame() {

    }

    public void onStateChanged(boolean playWhenReady, int playbackState) {

    }

    public boolean needRepeat() {
        return false;
    }

    public void seekTo(long position) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                pendingSeekTo = position;
                return;
            }
            videoPlayer.seekTo(position);
        });
    }

    public Uri getCurrentUri() {
        return contentUri;
    }

    public void setPlaybackSpeed(float currentVideoSpeed) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                return;
            }
            videoPlayer.setPlaybackSpeed(currentVideoSpeed);
        });

    }
}
