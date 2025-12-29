package com.lucasjosino.on_audio_query.controllers

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.lucasjosino.on_audio_query.PluginProvider

/** OnPlaylistsController */
class PlaylistController {

    //Main parameters
    private val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
    private val contentValues = ContentValues()
    private val channelError = "on_audio_error"
    private lateinit var resolver: ContentResolver

    //Query projection
    private val columns = arrayOf(
        "count(*)"
    )

    private val context = PluginProvider.context()
    private val result = PluginProvider.result()
    private val call = PluginProvider.call()

    //
    fun createPlaylist() {
        this.resolver = context.contentResolver
        val playlistName = call.argument<String>("playlistName")!!

        //For create we don't check if name already exist
        contentValues.put(MediaStore.Audio.Playlists.NAME, playlistName)
        contentValues.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis())
        resolver.insert(uri, contentValues)
        result.success(true)
    }

    //
    fun removePlaylist() {
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: 0L

        //Check if Playlist exists based in Id
        if (!checkPlaylistId(playlistId)) result.success(false)
        else {
            val delUri = ContentUris.withAppendedId(uri, playlistId)
            resolver.delete(delUri, null, null)
            result.success(true)
        }
    }

    //TODO Add option to use a list
    //TODO Fix error on Android 10
    fun addToPlaylist() {
        this.resolver = context.contentResolver
        val playlistId = call.argument<Int>("playlistId")!!
        val audioId = call.argument<Int>("audioId")!!


        //Check if Playlist exists based in Id
        if (!checkPlaylistId(playlistId)) result.success(false)
        else {
            val uri =
                MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId.toLong())
            //If Android is Q/10 or above "count(*)" don't count, so, we use other method.
            val columnsBasedOnVersion = if (Build.VERSION.SDK_INT < 29) columns else null
            val cursor = resolver.query(uri, columnsBasedOnVersion, null, null, null)
            var count = -1
            while (cursor != null && cursor.moveToNext()) {
                count += if (Build.VERSION.SDK_INT < 29) cursor.count else cursor.getInt(0)
            }
            cursor?.close()
            //
            try {
                contentValues.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, count + 1)
                contentValues.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId.toLong())
                resolver.insert(uri, contentValues)
                result.success(true)
            } catch (e: Exception) {
                Log.i(channelError, e.toString())
            }
        }
    }

    //TODO Add option to use a list
    fun removeFromPlaylist() {
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val audioId = call.argument<Number>("audioId")?.toLong() ?: return result.success(false)
        
        Log.d("on_audio_query", "removeFromPlaylist [VER 5]: Starting. Playlist: $playlistId, ID: $audioId")

        // 1. Validate Playlist
        if (!checkPlaylistId(playlistId)) {
            Log.w("on_audio_query", "removeFromPlaylist [VER 5]: Playlist $playlistId not found")
            result.success(false)
            return
        }

        try {
            val membersUri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
            
            // 2. Search for the member entries. 
            // This handles cases where audioId is either the Song ID (AUDIO_ID) or the Record ID (_ID).
            val projection = arrayOf(
                MediaStore.Audio.Playlists.Members._ID,
                MediaStore.Audio.Playlists.Members.AUDIO_ID
            )
            
            val cursor = resolver.query(membersUri, projection, null, null, null)
            val idsToDelete = mutableListOf<Long>()
            
            while (cursor != null && cursor.moveToNext()) {
                val memberId = cursor.getLong(0)
                val songId = cursor.getLong(1)
                
                // If the provided audioId matches either the entry itself or the underlying song
                if (memberId == audioId || songId == audioId) {
                    idsToDelete.add(memberId)
                }
            }
            cursor?.close()
            
            Log.d("on_audio_query", "removeFromPlaylist [VER 5]: Found ${idsToDelete.size} matching records to remove")

            if (idsToDelete.isEmpty()) {
                Log.w("on_audio_query", "removeFromPlaylist [VER 5]: No matching records found in playlist")
                result.success(false)
                return
            }

            // 3. Delete each matching entry by its specific URI
            var totalDeleted = 0
            for (id in idsToDelete) {
                val itemUri = ContentUris.withAppendedId(membersUri, id)
                val d = resolver.delete(itemUri, null, null)
                totalDeleted += d
                Log.d("on_audio_query", "removeFromPlaylist [VER 5]: Deleting member $id - result: $d")
            }

            result.success(totalDeleted > 0)
        } catch (e: Exception) {
            Log.e("on_audio_error", "removeFromPlaylist [VER 5]: Error occurred", e)
            result.success(false)
        }
    }

    //TODO("Need tests")
    fun moveItemTo() {
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val from = call.argument<Int>("from")!!
        val to = call.argument<Int>("to")!!

        //Check if Playlist exists based in Id
        if (!checkPlaylistId(playlistId)) result.success(false)
        else {
            MediaStore.Audio.Playlists.Members.moveItem(resolver, playlistId, from, to)
            result.success(true)
        }
    }

    //
    fun renamePlaylist() {
        this.resolver = context.contentResolver
        val playlistId = call.argument<Number>("playlistId")?.toLong() ?: return result.success(false)
        val newPlaylistName = call.argument<String>("newPlName")!!

        //Check if Playlist exists based in Id
        if (!checkPlaylistId(playlistId)) result.success(false)
        else {
            contentValues.clear()
            contentValues.put(MediaStore.Audio.Playlists.NAME, newPlaylistName)
            contentValues.put(MediaStore.Audio.Playlists.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            resolver.update(uri, contentValues, "_id=$playlistId", null)
            result.success(true)
        }
    }

    //Return true if playlist already exist, false if don't exist
    private fun checkPlaylistId(plId: Long): Boolean {
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Playlists._ID),
            MediaStore.Audio.Playlists._ID + "=?",
            arrayOf(plId.toString()),
            null
        )
        val exists = cursor != null && cursor.count > 0
        cursor?.close()
        return exists
    }
}

//Extras:

//I/PlaylistCursor[All]: [
//  title_key
// instance_id
// playlist_id
// duration
// is_ringtone
// album_artist
// orientation
// artist
// height
// is_drm
// bucket_display_name
// is_audiobook
// owner_package_name
// volume_name
// title_resource_uri
// date_modified
// date_expires
// composer
// _display_name
// datetaken
// mime_type
// is_notification
// _id
// year
// _data
// _hash
// _size
// album
// is_alarm
// title
// track
// width
// is_music
// album_key
// is_trashed
// group_id
// document_id
// artist_id
// artist_key
// is_pending
// date_added
// audio_id
// is_podcast
// album_id
// primary_directory
// secondary_directory
// original_document_id
// bucket_id
// play_order
// bookmark
// relative_path
// ]