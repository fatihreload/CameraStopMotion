package com.motionstudio.pro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionstudio.pro.MotionApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MotionApp).repo
    val projects = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun create(onCreated: (String) -> Unit) { viewModelScope.launch { onCreated(repo.create()) } }
    fun duplicate(id: String) { viewModelScope.launch { repo.duplicate(id) } }
    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
    fun rename(id: String, name: String) { viewModelScope.launch { repo.rename(id, name) } }
}
