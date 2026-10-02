package dev.pschmitt.jellyfin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.pschmitt.jellyfin.database.ServerDatabaseDao
import dev.pschmitt.jellyfin.models.JollyfinItem
import dev.pschmitt.jellyfin.models.toJollyfinEpisode
import dev.pschmitt.jellyfin.models.toJollyfinMovie
import dev.pschmitt.jellyfin.models.toJollyfinSource
import dev.pschmitt.jellyfin.repository.JellyfinRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var repository: JellyfinRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.DOWNLOAD_COMPLETE") {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != -1L) {
                val source = database.getSourceByDownloadId(id)
                if (source != null) {
                    val path = DownloadFileNaming.finalPath(source.path)
                    val successfulRename = File(source.path).renameTo(File(path))
                    if (successfulRename) {
                        database.setSourcePath(source.id, path)
                    } else {
                        val items = mutableListOf<JollyfinItem>()
                        items.addAll(
                            database.getMovies().map {
                                it.toJollyfinMovie(database, repository.getUserId())
                            }
                        )
                        items.addAll(
                            database.getEpisodes().map {
                                it.toJollyfinEpisode(database, repository.getUserId())
                            }
                        )

                        items
                            .firstOrNull { it.id == source.itemId }
                            ?.let {
                                CoroutineScope(Dispatchers.IO).launch {
                                    downloader.deleteItem(it, source.toJollyfinSource(database))
                                }
                            }
                    }
                } else {
                    val mediaStream = database.getMediaStreamByDownloadId(id)
                    if (mediaStream != null) {
                        val path = DownloadFileNaming.finalPath(mediaStream.path)
                        val successfulRename = File(mediaStream.path).renameTo(File(path))
                        if (successfulRename) {
                            database.setMediaStreamPath(mediaStream.id, path)
                        } else {
                            database.deleteMediaStream(mediaStream.id)
                        }
                    }
                }
            }
        }
    }
}
