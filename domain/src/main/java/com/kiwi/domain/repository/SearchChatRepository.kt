package com.kiwi.domain.repository

import com.kiwi.domain.model.ChatInfo
import com.kiwi.domain.model.Marker
import com.kiwi.domain.model.PlaceChatInfo
import kotlinx.coroutines.flow.Flow

interface SearchChatRepository {
    suspend fun getMarkerList(keywords: List<String>, x: Double, y: Double): Flow<Marker>

    suspend fun getChat(marker: Marker): PlaceChatInfo
}