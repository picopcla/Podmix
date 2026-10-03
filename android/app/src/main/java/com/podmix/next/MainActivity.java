package com.podmix.next;

import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.webkit.WebView;
import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;
import com.podmix.next.player.PodmixPlayerPlugin;

public class MainActivity extends BridgeActivity {
  public static final String PLAYBACK_TARGET_PREFERENCES = "podmix-playback-target";
  public static final String EXTRA_SOURCE_ID = "podmixSourceId";
  public static final String EXTRA_EPISODE_ID = "podmixEpisodeId";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    WebView.setWebContentsDebuggingEnabled(
      (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
    );
    registerPlugin(PodmixPlayerPlugin.class);
    super.onCreate(savedInstanceState);
    PodmixPlayerPlugin.markBoseAppSessionActive(this);
    rememberPlaybackTarget(getIntent());
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    rememberPlaybackTarget(intent);
  }

  private void rememberPlaybackTarget(Intent intent) {
    if (intent == null) return;
    String sourceId = intent.getStringExtra(EXTRA_SOURCE_ID);
    if (sourceId == null || sourceId.isBlank()) return;
    getSharedPreferences(PLAYBACK_TARGET_PREFERENCES, MODE_PRIVATE).edit()
      .putString(EXTRA_SOURCE_ID, sourceId)
      .putString(EXTRA_EPISODE_ID, intent.getStringExtra(EXTRA_EPISODE_ID))
      .apply();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    // Sur les Fold, le WebView reçoit bien le changement de configuration mais
    // peut conserver la largeur de l'écran externe. Forcer une nouvelle mesure
    // évite cette fenêtre étroite ancrée à gauche après l'ouverture du téléphone.
    View content = getWindow().getDecorView();
    content.post(() -> {
      content.requestLayout();
      content.invalidate();
    });
  }
}
