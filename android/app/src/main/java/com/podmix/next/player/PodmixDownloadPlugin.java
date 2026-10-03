package com.podmix.next.player;

import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Point d'entrée Capacitor historique du téléchargement.
 *
 * Il doit déléguer au gestionnaire complet du parent : l'ancienne
 * implémentation autonome créait une requête DownloadManager sans enregistrer
 * son identifiant dans les préférences. L'interface ne pouvait alors jamais
 * suivre les octets et restait indéfiniment à 0 %.
 */
@CapacitorPlugin(name = "PodmixPlayer")
public class PodmixDownloadPlugin extends PodmixPlayerPlugin {
    @Override
    @PluginMethod
    public void download(PluginCall call) {
        super.download(call);
    }
}
