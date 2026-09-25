package com.example.gusa

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityLoginBinding
import com.example.gusa.util.ThemeHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException

class LoginActivity : FragmentActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var cachedLookupDoc: DocumentSnapshot? = null

    private object Cols { const val ADM = "admins"; const val DRV = "drivers"; const val STD = "students"; const val UNI = "universities" }
    private object Keys { const val ADM_ID = "ADMIN_ID"; const val DRV_ID = "DRIVER_ID"; const val STD_ID = "STUDENT_ID" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applySavedTheme(this)
        binding = ActivityLoginBinding.inflate(layoutInflater).also { setContentView(it.root) }
        checkActiveSession()
        setupListeners()
        animateEntrance()
    }

    private fun checkActiveSession() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            setLoadingState(true, getString(R.string.restoring_session))
            routeUser(currentUser.uid)
        }
    }

    private fun animateEntrance() {
        binding.layoutBranding.apply { alpha = 0f; translationY = -60f; animate().alpha(1f).translationY(0f).setDuration(1000).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start() }
        binding.cardLogin.apply { alpha = 0f; translationY = 80f; animate().alpha(1f).translationY(0f).setDuration(1000).setStartDelay(300).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start() }
        binding.btnLogin.apply { alpha = 0f; animate().alpha(1f).setDuration(1000).setStartDelay(600).start() }
    }

    private fun setupListeners() {
        binding.tvGoToRegister.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        binding.btnForgotPassword.setOnClickListener { showFeedback("Password recovery coming soon") }
        binding.btnLogin.setOnClickListener {
            val input = binding.etEmail.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            if (input.isEmpty() || pass.isEmpty()) return@setOnClickListener showFeedback(getString(R.string.error_empty_fields))
            hideKeyboard()
            setLoadingState(true, "Signing in...")
            lifecycleScope.launch {
                try {
                    val target = if (input.contains("@")) input else {
                        setLoadingState(true, "Looking up ID...")
                        (lookup(Cols.ADM, "adminId", input) ?: lookup(Cols.DRV, "driverId", input) ?: lookup(Cols.STD, "studentId", input))?.also { cachedLookupDoc = it }?.getString("email") ?: throw Exception(getString(R.string.error_user_not_found))
                    }
                    setLoadingState(true, "Authenticating account...")
                    val uid = auth.signInWithEmailAndPassword(target, pass).await().user?.uid ?: throw Exception(getString(R.string.error_auth_failed))
                    
                    setLoadingState(true, "Loading profile...")
                    routeUser(uid)
                } catch (e: Exception) { 
                    handleError(e) 
                    setLoadingState(false)
                }
            }
        }
    }

    private fun routeUser(uid: String) = lifecycleScope.launch {
        try {
            val doc = cachedLookupDoc?.takeIf { it.id == uid } 
                ?: db.collection(Cols.ADM).document(uid).get().await().takeIf { it.exists() } 
                ?: db.collection(Cols.DRV).document(uid).get().await().takeIf { it.exists() } 
                ?: db.collection(Cols.STD).document(uid).get().await().takeIf { it.exists() } 
                ?: throw Exception(getString(R.string.error_profile_not_found))
            
            setLoadingState(true, "Preparing dashboard...")
            dispatch(doc)
        } catch (e: Exception) { 
            handleError(e) 
            setLoadingState(false)
        }
    }

    private suspend fun dispatch(doc: DocumentSnapshot) {
        val path = doc.reference.path
        val uid = doc.id
        when {
            path.contains(Cols.ADM) -> { 
                validateUni(doc, "admin")
                nav(AdminActivity::class.java, Keys.ADM_ID, doc.getString("adminId") ?: uid, uid)
            }
            path.contains(Cols.DRV) -> { 
                validateUni(doc, "driver")
                nav(DriverActivity::class.java, Keys.DRV_ID, doc.getString("driverId") ?: uid, uid)
            }
            path.contains(Cols.STD) -> { 
                validateUni(doc, "student")
                nav(StudentActivity::class.java, Keys.STD_ID, doc.getString("studentId") ?: uid, uid)
            }
        }
    }

    private fun nav(cls: Class<*>, key: String, id: String, uid: String? = null) {
        startActivity(Intent(this, cls).apply { putExtra(key, id); uid?.let { putExtra("USER_UID", it) } })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finishAffinity()
    }

    private suspend fun validateUni(doc: DocumentSnapshot, role: String) {
        val uniId = doc.getString("universityId")
        if (!uniId.isNullOrEmpty()) {
            setLoadingState(true, "Validating university...")
            val uni = db.collection(Cols.UNI).document(uniId).get().await()
            if (uni.exists() && !(uni.getBoolean("active") ?: true)) throw Exception("University Disabled")
        } else if (role != "admin") Log.w("LoginActivity", "Uni missing for ${doc.id}")
    }

    private suspend fun lookup(col: String, field: String, value: String) = db.collection(col).whereEqualTo(field, value).get().await().documents.getOrNull(0)

    private fun handleError(e: Exception) {
        Log.e("LoginActivity", "Auth fault", e)
        val msg = when (e) {
            is IOException -> getString(R.string.no_internet)
            is FirebaseNetworkException -> getString(R.string.server_unavailable)
            is FirebaseFirestoreException -> getString(R.string.firestore_error)
            is FirebaseAuthInvalidUserException -> getString(R.string.invalid_id)
            is FirebaseAuthInvalidCredentialsException -> getString(R.string.wrong_password)
            else -> {
                val rawMsg = e.message ?: ""
                when {
                    rawMsg.contains("CONFIGURATION_NOT_FOUND") -> 
                        "Auth configuration error. Please ensure: 1. Email/Password is enabled in Firebase. 2. 'Identity Toolkit API' is enabled in Google Cloud Console for project '${FirebaseApp.getInstance().options.projectId}'."
                    rawMsg.contains("identitytoolkit") && rawMsg.contains("blocked") -> 
                        getString(R.string.error_auth_blocked)
                    else -> rawMsg.ifEmpty { getString(R.string.auth_failed_generic) }
                }
            }
        }
        showFeedback(msg)
        if (msg.contains("ID") || msg.contains("User") || msg.contains("Admin")) binding.etEmail.requestFocus()
        else if (msg.contains("Password")) binding.etPassword.requestFocus()
    }

    private fun setLoadingState(loading: Boolean, text: String = "") {
        binding.btnLogin.isEnabled = !loading
        binding.tilEmail.isEnabled = !loading
        binding.tilPassword.isEnabled = !loading
        binding.tvGoToRegister.isEnabled = !loading
        
        if (loading) {
            binding.btnLogin.alpha = 0.5f
            binding.layoutLoadingStatus.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = text
        } else {
            binding.btnLogin.alpha = 1.0f
            binding.layoutLoadingStatus.visibility = View.GONE
        }
    }

    private fun hideKeyboard() = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    private fun showFeedback(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
