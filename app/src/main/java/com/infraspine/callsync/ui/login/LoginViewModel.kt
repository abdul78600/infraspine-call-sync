package com.infraspine.callsync.ui.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.repository.AuthRepository
import com.infraspine.callsync.data.repository.LoginResult
import com.infraspine.callsync.ui.common.Event
import kotlinx.coroutines.launch

sealed class LoginUiEvent {
    object Success : LoginUiEvent()
    data class Error(val message: String) : LoginUiEvent()
}

class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _isLoggingIn = MutableLiveData(false)
    val isLoggingIn: LiveData<Boolean> = _isLoggingIn

    private val _event = MutableLiveData<Event<LoginUiEvent>>()
    val event: LiveData<Event<LoginUiEvent>> = _event

    fun login(serverUrl: String, email: String, password: String) {
        if (_isLoggingIn.value == true) return

        viewModelScope.launch {
            _isLoggingIn.value = true
            val result = authRepository.login(serverUrl, email, password)
            _isLoggingIn.value = false

            _event.value = Event(
                when (result) {
                    LoginResult.Success -> LoginUiEvent.Success
                    is LoginResult.Failure -> LoginUiEvent.Error(result.message)
                }
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(container.authRepository) as T
        }
    }
}
