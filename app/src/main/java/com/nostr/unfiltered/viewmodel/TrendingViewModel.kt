package com.nostr.unfiltered.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nostr.unfiltered.nostr.TrendingPost
import com.nostr.unfiltered.nostr.ZapVelocityService
import com.nostr.unfiltered.repository.ZapDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ZapBoost Trending ViewModel
 * Manages zap velocity data and trending posts
 */
@HiltViewModel
class TrendingViewModel @Inject constructor(
    application: Application,
    private val zapVelocityService: ZapVelocityService
) : AndroidViewModel(application) {
    
    private val _trendingPosts = MutableStateFlow<List<TrendingPost>>(emptyList())
    val trendingPosts: StateFlow<List<TrendingPost>> = _trendingPosts.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        // Start monitoring zap receipts
        zapVelocityService.startMonitoring()
        
        // Load initial trending posts
        loadTrendingPosts()
    }
    
    private fun loadTrendingPosts() {
        viewModelScope.launch {
            try {
                val posts = zapVelocityService.getTrendingPosts(limit = 50)
                _trendingPosts.value = posts
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun refresh() {
        _isLoading.value = true
        loadTrendingPosts()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Don't stop monitoring - service is shared across the app
    }
}
