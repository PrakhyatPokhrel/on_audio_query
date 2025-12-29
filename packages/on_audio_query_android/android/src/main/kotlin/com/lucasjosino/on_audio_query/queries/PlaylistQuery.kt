package com.lucasjosino.on_audio_query.queries

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucasjosino.on_audio_query.PluginProvider
import com.lucasjosino.on_audio_query.controllers.PermissionController
import com.lucasjosino.on_audio_query.queries.helper.QueryHelper
import com.lucasjosino.on_audio_query.types.checkPlaylistsUriType
import com.lucasjosino.on_audio_query.types.sorttypes.checkGenreSortType
import com.lucasjosino.on_audio_query.utils.playlistProjection
import io.flutter.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OnPlaylistQuery */
class PlaylistQuery : ViewModel() {

    companion object {
        private const val TAG = "OnPlaylistQuery"
    }

    //Main parameters
    private val helper = QueryHelper()

    private lateinit var uri: Uri
    private lateinit var resolver: ContentResolver
    private lateinit var sortType: String

    private val contentValues = ContentValues()
    private val channelError = "on_audio_error"

    /**
     * Method to "query" all playlists.
     */
    fun queryPlaylists() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver

        // Sort: Type and Order.
        sortType = checkGenreSortType(
            call.argument<Int>("sortType"),
            call.argument<Int>("orderType")!!,
            call.argument<Boolean>("ignoreCase")!!
        )
        // Check uri:
        //   * 0 -> External.
        //   * 1 -> Internal.
        uri = checkPlaylistsUriType(call.argument<Int>("uri")!!)

        Log.d(TAG, "Query config: ")
        Log.d(TAG, "\tsortType: $sortType")
        Log.d(TAG, "\turi: $uri")

        // Query everything in background for a better performance.
        viewModelScope.launch {
            val queryResult = loadPlaylists()
            result.success(queryResult)
        }
    }

    //
    fun createPlaylist() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistName = call.argument<String>("playlistName")!!

        //For create we don't check if name already exist
        val writeUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        contentValues.put(MediaStore.Audio.Playlists.NAME, playlistName)
        contentValues.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis() / 1000)
        resolver.insert(writeUri, contentValues)
        result.success(true)
    }

    //
    fun removePlaylist() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: 0L

        viewModelScope.launch {
            //Check if Playlist exists based in Id
            if (!checkPlaylistId(playlistId)) {
                result.success(false)
            } else {
                withContext(Dispatchers.IO) {
                    val writeUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
                    val delUri = ContentUris.withAppendedId(writeUri, playlistId)
                    resolver.delete(delUri, null, null)
                }
                result.success(true)
            }
        }
    }

    fun addToPlaylist() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val audioId = call.argument<Number>("audioId")?.toLong() ?: return result.success(false)

        viewModelScope.launch {
            //Check if Playlist exists based in Id
            if (!checkPlaylistId(playlistId)) {
                result.success(false)
            } else {
                withContext(Dispatchers.IO) {
                    val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
                    //If Android is Q/10 or above "count(*)" don't count, so, we use other method.
                    val columns = arrayOf("count(*)")
                    val columnsBasedOnVersion = if (Build.VERSION.SDK_INT < 29) columns else null
                    val cursor = resolver.query(membersUri, columnsBasedOnVersion, null, null, null)
                    var count = -1
                    while (cursor != null && cursor.moveToNext()) {
                        count += if (Build.VERSION.SDK_INT < 29) cursor.count else cursor.getInt(0)
                    }
                    cursor?.close()
                    //
                    try {
                        contentValues.clear()
                        contentValues.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, count + 1)
                        contentValues.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId)
                        resolver.insert(membersUri, contentValues)
                    } catch (e: Exception) {
                        Log.e(TAG, e.toString())
                    }
                }
                result.success(true)
            }
        }
    }

    // Exhaustive remove method (VER 7)
    fun removeFromPlaylist() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val audioId = call.argument<Number>("audioId")?.toLong() ?: return result.success(false)
        
        Log.d(TAG, "removeFromPlaylist [VER 7]: Request - Playlist: $playlistId, ID: $audioId")

        viewModelScope.launch {
            if (!checkPlaylistId(playlistId)) {
                Log.w(TAG, "removeFromPlaylist [VER 7]: Playlist $playlistId not found")
                result.success(false)
                return@launch
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
                    
                    // 1. Search for matching entries
                    val projection = arrayOf(
                        MediaStore.Audio.Playlists.Members._ID,
                        MediaStore.Audio.Playlists.Members.AUDIO_ID
                    )
                    
                    val cursor = resolver.query(membersUri, projection, null, null, null)
                    val idsToDelete = mutableListOf<Long>()
                    
                    Log.d(TAG, "removeFromPlaylist [VER 7]: Scanning members...")
                    while (cursor != null && cursor.moveToNext()) {
                        val mId = cursor.getLong(0)
                        val sId = cursor.getLong(1)
                        Log.d(TAG, "   Member: _ID=$mId, AUDIO_ID=$sId")
                        if (mId == audioId || sId == audioId) {
                            idsToDelete.add(mId)
                        }
                    }
                    cursor?.close()
                    
                    if (idsToDelete.isEmpty()) {
                        Log.w(TAG, "removeFromPlaylist [VER 7]: ID $audioId not found in playlist $playlistId")
                        return@withContext false
                    }

                    // 2. Aggressive deletion
                    var totalDeleted = 0
                    for (id in idsToDelete) {
                        // Method A: Deletion by Item URI
                        val itemUri = ContentUris.withAppendedId(membersUri, id)
                        var d = resolver.delete(itemUri, null, null)
                        
                        // Method B: Deletion by Selection (Fallback)
                        if (d == 0) {
                            d = resolver.delete(membersUri, "${MediaStore.Audio.Playlists.Members._ID} = ?", arrayOf(id.toString()))
                        }
                        
                        totalDeleted += d
                        Log.d(TAG, "removeFromPlaylist [VER 7]: Deleted record $id - result: $d")
                    }
                    
                    // Method C: Fallback to bulk delete by AUDIO_ID if individual deletion failed
                    if (totalDeleted == 0) {
                        Log.w(TAG, "removeFromPlaylist [VER 7]: Individual deletion failed, trying bulk delete by AUDIO_ID...")
                        val d = resolver.delete(
                            membersUri,
                            "${MediaStore.Audio.Playlists.Members.AUDIO_ID} = ?",
                            arrayOf(audioId.toString())
                        )
                        totalDeleted += d
                        Log.d(TAG, "removeFromPlaylist [VER 7]: Bulk deletion result: $d")
                    }
                    
                    totalDeleted > 0
                } catch (e: Exception) {
                    Log.e(TAG, "removeFromPlaylist [VER 7]: Error", e)
                    false
                }
            }
            result.success(success)
        }
    }

    fun moveItemTo() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val from = call.argument<Int>("from")!!
        val to = call.argument<Int>("to")!!

        viewModelScope.launch {
            if (!checkPlaylistId(playlistId)) {
                result.success(false)
            } else {
                withContext(Dispatchers.IO) {
                    MediaStore.Audio.Playlists.Members.moveItem(resolver, playlistId, from, to)
                }
                result.success(true)
            }
        }
    }

    //
    fun renamePlaylist() {
        val call = PluginProvider.call()
        val result = PluginProvider.result()
        val context = PluginProvider.context()
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val newPlaylistName = call.argument<String>("newPlName")!!

        viewModelScope.launch {
            if (!checkPlaylistId(playlistId)) {
                result.success(false)
            } else {
                withContext(Dispatchers.IO) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Playlists.NAME, newPlaylistName)
                    contentValues.put(MediaStore.Audio.Playlists.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    val writeUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
                    resolver.update(writeUri, contentValues, "_id=$playlistId", null)
                }
                result.success(true)
            }
        }
    }

    //Return true if playlist already exist, false if don't exist
    private suspend fun checkPlaylistId(plId: Long): Boolean = withContext(Dispatchers.IO) {
        val writeUri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val cursor = resolver.query(
            writeUri,
            arrayOf(MediaStore.Audio.Playlists._ID),
            "${MediaStore.Audio.Playlists._ID} = ?",
            arrayOf(plId.toString()),
            null
        )
        val exists = cursor != null && cursor.count > 0
        cursor?.close()
        exists
    }

    //Loading in Background
    private suspend fun loadPlaylists(): ArrayList<MutableMap<String, Any?>> =
        withContext(Dispatchers.IO) {
            // Setup the cursor with 'uri' and 'projection'.
            val cursor = resolver.query(uri, playlistProjection, null, null, null)

            val playlistList: ArrayList<MutableMap<String, Any?>> = ArrayList()

            Log.d(TAG, "Cursor count: ${cursor?.count}")

            // For each item(playlist) inside this "cursor", take one and "format"
            // into a 'Map<String, dynamic>'.
            while (cursor != null && cursor.moveToNext()) {
                val playlistData: MutableMap<String, Any?> = HashMap()

                for (playlistMedia in cursor.columnNames) {
                    playlistData[playlistMedia] = helper.loadPlaylistItem(playlistMedia, cursor)
                }

                // Count and add the number of songs for every playlist.
                val mediaCount = helper.getMediaCount(1, playlistData["_id"].toString(), resolver)
                playlistData["num_of_songs"] = mediaCount

                playlistList.add(playlistData)
            }

            // Close cursor to avoid memory leaks.
            cursor?.close()
            return@withContext playlistList
        }
}
