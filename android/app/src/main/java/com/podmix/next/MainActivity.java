package com.podmix.next;

import android.os.Bundle;
import android.webkit.WebView;
import android.content.pm.ApplicationInfo;

import com.getcapacitor.BridgeActivity;
import com.podmix.next.player.PodmixPlayerPlugin;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    WebView.setWebContentsDebuggingEnabled(
      (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
    );
    registerPlugin(PodmixPlayerPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
