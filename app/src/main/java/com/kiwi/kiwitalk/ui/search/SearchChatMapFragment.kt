package com.kiwi.kiwitalk.ui.search

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.ktx.awaitMap
import com.google.maps.android.ktx.mapClickEvents
import com.kiwi.domain.model.ChatInfo
import com.kiwi.kiwitalk.R
import com.kiwi.kiwitalk.databinding.FragmentSearchChatMapBinding
import com.kiwi.kiwitalk.model.ClusterMarker
import com.kiwi.kiwitalk.model.ClusterMarker.Companion.toClusterMarker
import com.kiwi.kiwitalk.ui.keyword.SearchKeywordViewModel
import com.kiwi.kiwitalk.ui.keyword.recyclerview.SelectedKeywordAdapter
import com.kiwi.kiwitalk.ui.newchat.NewChatActivity
import dagger.hilt.android.AndroidEntryPoint
import io.getstream.chat.android.ui.message.MessageListActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchChatMapFragment : Fragment(), ChatDialogAction {
    private var _binding: FragmentSearchChatMapBinding? = null
    val binding get() = _binding!!
    private val chatViewModel: SearchChatMapViewModel by viewModels()
    private val keywordViewModel: SearchKeywordViewModel by activityViewModels()

    private val fusedLocationClient
            by lazy { LocationServices.getFusedLocationProviderClient(requireActivity()) }
    private lateinit var map: GoogleMap

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<CoordinatorLayout>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback

    private val activityResultLauncher = initPermissionLauncher()
    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private var dialog: ChatJoinDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding =
            DataBindingUtil.inflate(inflater, R.layout.fragment_search_chat_map, container, false)
        initPermissionLauncher()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.vm = chatViewModel
        initMap()
        initToolbar()
        initAdapter()
        initBottomSheetCallBack()
        initKeywordRecyclerView()
        initScreenChange()
        recoverUiState()
    }

    private fun initAdapter() {
        val previewAdapter = ChatAdapter(mutableListOf()) {
            showChatDialog(it)
        }
        binding.layoutMarkerInfoPreview.rvPreviewChat.apply {
            adapter = previewAdapter
            layoutManager = GridLayoutManager(context, 1)
        }

        val detailAdapter = ChatAdapter(mutableListOf()) {
            showChatDialog(it)
        }

        binding.rvDetail.apply {
            adapter = detailAdapter
            layoutManager = GridLayoutManager(context, 1)
        }

        chatViewModel.placeChatInfo.observe(viewLifecycleOwner) { placeChatInfo ->
            placeChatInfo.getPopularChat()?.let {
                previewAdapter.submitList(mutableListOf(it))
            }
            detailAdapter.submitList(placeChatInfo.chatList)
        }
    }

    private fun initScreenChange() {
        binding.fabCreateChat.setOnClickListener {
            startActivity(Intent(requireContext(), NewChatActivity::class.java))
        }
    }

    private fun showChatDialog(chatInfo: ChatInfo) {
        dialog = ChatJoinDialog(this, chatInfo)
        dialog?.show(childFragmentManager, "Chat_Join_Dialog")
        chatViewModel.dialogData = chatInfo
    }

    override fun onClickJoinButton(cid: String) {
        chatViewModel.dialogData = null
        startChat(cid)
    }

    override fun onClickCancleButton() {
        chatViewModel.dialogData = null
    }

    private fun startChat(cid: String) {
        lifecycleScope.launch {
            chatViewModel.appendUserToChat(cid)
            if (Regex(".+:.+").matches(cid)) {
                startActivity(MessageListActivity.createIntent(requireContext(), cid))
            }
        }
    }

    private fun recoverUiState() {
        chatViewModel.dialogData?.let {
            showChatDialog(it)
        }
        when (val state = chatViewModel.lastBottomSheetState) {
            BottomSheetBehavior.STATE_COLLAPSED -> {
                bottomSheetBehavior.state = state
                showBottomSheetPreview()
            }
            BottomSheetBehavior.STATE_EXPANDED -> {
                bottomSheetBehavior.state = state
                showBottomSheetDetail()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initPermissionLauncher(): ActivityResultLauncher<Array<String>> {
        return registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionResult ->
            if (permissionResult.values.all { it }) {
                map.uiSettings.isMyLocationButtonEnabled = true
                map.isMyLocationEnabled = true
                getDeviceLocation()
            } else {
                map.uiSettings.isMyLocationButtonEnabled = false
                map.isMyLocationEnabled = false
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.3587, 127.1051), 15f))
                Toast.makeText(requireContext(), "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initMap() {
        val mapFragment =
            childFragmentManager.findFragmentById(R.id.fragment_searchChat_map_container) as? SupportMapFragment
                ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            map = mapFragment.awaitMap()
            val styleOption = resources.assets.open("map_style.json").reader().readText()
            val mapStyleOptions = MapStyleOptions(styleOption)
            map.setMapStyle(mapStyleOptions)


            map.setMinZoomPreference(5.0F)
            map.setMaxZoomPreference(20.0F)
            map.clear()
            map.setMinZoomPreference(10f)
            getDeviceLocation(permissions)
            setUpCluster()
        }
        chatViewModel.getMarkerList(keywordViewModel.selectedKeyword.value)
        chatViewModel.location.observe(viewLifecycleOwner) {
            moveToLocation(it)
            chatViewModel.location.removeObservers(viewLifecycleOwner)
        }
    }

    private fun setUpCluster() {
        val clusterManager = ClusterManager<ClusterMarker>(requireContext(), map)
        val clusterRenderer = ClusterMarkerRenderer(requireContext(), map, clusterManager)
        clusterManager.renderer = clusterRenderer
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.markerList.collect {
                    clusterManager.addItem(it.toClusterMarker())
                }
            }
        }
        clusterManager.markerCollection.setInfoWindowAdapter(object : InfoWindowAdapter {
            override fun getInfoContents(p0: com.google.android.gms.maps.model.Marker): View {
                return View(requireContext())
            }

            override fun getInfoWindow(p0: com.google.android.gms.maps.model.Marker): View {
                return View(requireContext())
            }
        })
        chatViewModel.isLoadingMarkerList.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading.not()) {
                clusterManager.cluster()
            }
        }
        map.setOnCameraIdleListener(clusterManager)
        setupMapClickListener(clusterManager)
    }

    private fun setupMapClickListener(clusterManager: ClusterManager<ClusterMarker>) {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                map.mapClickEvents().collectLatest {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
            }
        }
        clusterManager.setOnClusterItemClickListener { item ->
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            chatViewModel.getPlaceInfo(listOf(item.cid))
            false
        }
        clusterManager.setOnClusterClickListener { cluster ->
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            cluster.items.forEach {
                Log.d("SearchChatActivity", "forEach: $it")
            }
            chatViewModel.getPlaceInfo(cluster.items.map { it.cid })
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceLocation() {
        fusedLocationClient.lastLocation.addOnCompleteListener(requireActivity()) { task ->
            task.addOnSuccessListener { location: Location? ->
                location ?: return@addOnSuccessListener
                chatViewModel.setDeviceLocation(location)
            }
        }
    }

    private fun moveToLocation(location: Location?) {
        Log.d(TAG, "moveToLocation: $location")
        if (location == null || ::map.isInitialized.not()) return
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(location.latitude, location.longitude), 13f
            )
        )
    }

    private fun getDeviceLocation(permissions: Array<String>) {
        activityResultLauncher.launch(permissions)
    }

    private fun initBottomSheetCallBack() {
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_DRAGGING -> showBottomSheetDetail()
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        showBottomSheetPreview()
                        chatViewModel.lastBottomSheetState = newState
                    }
                    BottomSheetBehavior.STATE_SETTLING -> true
                    else -> chatViewModel.lastBottomSheetState = newState
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        }

        bottomSheetBehavior = BottomSheetBehavior
            .from(binding.layoutBottomSheet)
            .apply {
                addBottomSheetCallback(bottomSheetCallback)
                state = BottomSheetBehavior.STATE_HIDDEN
                isDraggable = true
                hideFriction = 0.01F
                isFitToContents = true // default
            }
    }

    private fun showBottomSheetDetail() {
        binding.layoutMarkerInfoPreview.rootLayout.visibility = View.INVISIBLE
        binding.tvTmpChatDetail.visibility = View.VISIBLE
        binding.rvDetail.visibility = View.VISIBLE
    }

    private fun showBottomSheetPreview() {
        binding.layoutMarkerInfoPreview.rootLayout.visibility = View.VISIBLE
        binding.tvTmpChatDetail.visibility = View.INVISIBLE
        binding.rvDetail.visibility = View.INVISIBLE
    }

    private fun initToolbar() {
        binding.searchChatMapToolbar.setNavigationOnClickListener {
            activity?.finish()
        }
        binding.searchChatMapToolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.item_action_search_keyword -> {
                    Navigation.findNavController(binding.root).navigate(
                        R.id.action_searchChatFragment_to_searchKeywordFragment
                    )
                    true
                }
                else -> false
            }
        }
    }

    private fun initKeywordRecyclerView() {
        val keywords = keywordViewModel.selectedKeyword.value
        binding.tvSearchChatKeywordsHint.isVisible = keywords.isNullOrEmpty()
        val adapter = SelectedKeywordAdapter()
        adapter.submitList(keywords)
        binding.rvSearchChatKeywords.adapter = adapter
    }

    override fun onPause() {
        super.onPause()
        dialog?.dismiss() // recover 대상에서 제외
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        _binding = null
    }

    companion object {
        const val TAG = "SearchChatMapFragment"
    }
}
