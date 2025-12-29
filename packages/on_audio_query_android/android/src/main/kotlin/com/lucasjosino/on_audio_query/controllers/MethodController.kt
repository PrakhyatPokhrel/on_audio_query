package com.lucasjosino.on_audio_query.controllers

import com.lucasjosino.on_audio_query.PluginProvider
import com.lucasjosino.on_audio_query.consts.Method
import com.lucasjosino.on_audio_query.queries.*

class MethodController() {

    //
    fun find() {
        when (PluginProvider.call().method) {
            //Query methods
            Method.QUERY_AUDIOS -> AudioQuery().querySongs()
            Method.QUERY_ALBUMS -> AlbumQuery().queryAlbums()
            Method.QUERY_ARTISTS -> ArtistQuery().queryArtists()
            Method.QUERY_PLAYLISTS -> PlaylistQuery().queryPlaylists()
            Method.QUERY_GENRES -> GenreQuery().queryGenres()
            Method.QUERY_ARTWORK -> ArtworkQuery().queryArtwork()
            Method.QUERY_AUDIOS_FROM -> AudioFromQuery().querySongsFrom()
            Method.QUERY_WITH_FILTERS -> WithFiltersQuery().queryWithFilters()
            Method.QUERY_ALL_PATHS -> AllPathQuery().queryAllPath()
            //Playlists methods
            Method.CREATE_PLAYLIST -> PlaylistQuery().createPlaylist()
            Method.REMOVE_PLAYLIST -> PlaylistQuery().removePlaylist()
            Method.ADD_TO_PLAYLIST -> PlaylistQuery().addToPlaylist()
            Method.REMOVE_FROM_PLAYLIST -> PlaylistQuery().removeFromPlaylist()
            Method.RENAME_PLAYLIST -> PlaylistQuery().renamePlaylist()
            Method.MOVE_ITEM_TO -> PlaylistQuery().moveItemTo()
            else -> PluginProvider.result().notImplemented()
        }
    }
}