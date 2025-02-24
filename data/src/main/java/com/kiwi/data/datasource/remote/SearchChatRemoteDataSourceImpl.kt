package com.kiwi.data.datasource.remote

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObjects
import com.kiwi.chatmapper.ChatKey
import com.kiwi.data.Const
import com.kiwi.data.mapper.Mapper.toChatInfo
import com.kiwi.data.mapper.Mapper.toMarker
import com.kiwi.data.model.remote.MarkerRemote
import com.kiwi.domain.model.ChatInfo
import com.kiwi.domain.model.Marker
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.api.models.QueryChannelsRequest
import io.getstream.chat.android.client.api.models.querysort.QuerySortByField
import io.getstream.chat.android.client.models.Filters
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

class SearchChatRemoteDataSourceImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val chatClient: ChatClient,
) : SearchChatRemoteDataSource {
    override fun getMarkerList(
        keyword: List<String>,
        x: Double,
        y: Double
    ): Flow<Marker> = callbackFlow {
        firestore.collection(Const.CHAT_COLLECTION)
            .whereArrayContainsAny(ChatKey.CHAT_KEYWORDS, keyword).get()
            .addOnSuccessListener { querySnapshot ->
                querySnapshot.toObjects<MarkerRemote>().filter { markerRemote ->
                    markerRemote.lat in x.toRange && markerRemote.lng in y.toRange
                }.forEach { markerRemote ->
                    trySend(markerRemote.toMarker())
                }
                close()
            }.addOnFailureListener {
                cancel()
            }
        awaitClose()
    }

    override suspend fun getChatList(cidList: List<String>): List<ChatInfo>? {
        val request = QueryChannelsRequest(
            filter = Filters.and(
                Filters.`in`("cid", cidList)
            ),
            offset = 0,
            limit = cidList.size,
            querySort = QuerySortByField.descByName("memberCount")
        ).apply {
            watch = true // if true returns the Channel state
            state = false // if true listen to changes to this Channel in real time.
            limit = cidList.size
        }

        val result = chatClient.queryChannels(request).await()
        return if (result.isSuccess) {
            Log.d(TAG, result.toString())
            result.data().map { it.toChatInfo() }
        } else {
            Log.d(TAG, result.toString())
            null
        }
    }

    override fun appendUserToChat(cid: String, userId: String) {
        val targetChannel = chatClient.channel(cid)
        targetChannel.addMembers(listOf(userId)).enqueue {
            if (it.isSuccess) {
                Log.d(TAG, "초대 성공")
            } else {
                Log.d(TAG, "초대 실패")
            }
        }
    }

    companion object {
        private const val TAG = "k001|SearchChatRemote"
        private val Double.toRange get() = this - 0.015..this + 0.015 //약 -1.5km~+1.5km
    }
}