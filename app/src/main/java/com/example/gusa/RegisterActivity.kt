package com.example.gusa

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.gusa.databinding.ActivityRegisterBinding
import com.example.gusa.model.Route
import com.example.gusa.model.Stop
import com.example.gusa.model.University
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class RegisterActivity : FragmentActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    
    private val universityList = mutableListOf<University>()
    private val routeList = mutableListOf<Route>()
    private val stopList = mutableListOf<Stop>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.gusa.util.ThemeHelper.applySavedTheme(this)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRoleListener()
        fetchUniversities()
        setupSpinnerListeners()
        
        binding.btnRegister.setOnClickListener {
            executeRegistrationFlow()
        }

        binding.tvGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupRoleListener() {
        binding.rgRole.setOnCheckedChangeListener { _, checkedId ->
            binding.layoutStudentFields.visibility = if (checkedId == R.id.rbStudent) View.VISIBLE else View.GONE
        }
    }

    private fun fetchUniversities() {
        lifecycleScope.launch {
            try {
                val snapshot = db.collection("universities").get().await()
                universityList.clear()
                val uniNames = mutableListOf<String>()
                
                for (doc in snapshot.documents) {
                    val uni = doc.toObject(University::class.java)
                    if (uni != null) {
                        universityList.add(uni)
                        uniNames.add(uni.name)
                    }
                }
                
                val adapter = ArrayAdapter(this@RegisterActivity, android.R.layout.simple_spinner_item, uniNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerUniversities.adapter = adapter
            } catch (e: Exception) {
                Toast.makeText(this@RegisterActivity, "Error loading universities", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSpinnerListeners() {
        binding.spinnerUniversities.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedUni = universityList[position]
                fetchRoutes(selectedUni.universityId.toString())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerRoutes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedRoute = routeList[position]
                fetchStops(selectedRoute.routeId.toString())
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun fetchRoutes(universityId: Any?) {
        val uniId = universityId.toString()
        lifecycleScope.launch {
            try {
                val snapshot = db.collection("routes")
                    .whereEqualTo("universityId", uniId)
                    .get().await()
                
                routeList.clear()
                val routeNames = mutableListOf<String>()
                
                for (doc in snapshot.documents) {
                    val route = doc.toObject(Route::class.java)
                    if (route != null) {
                        routeList.add(route)
                        routeNames.add(route.routeName)
                    }
                }
                
                val adapter = ArrayAdapter(this@RegisterActivity, android.R.layout.simple_spinner_item, routeNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerRoutes.adapter = adapter
            } catch (e: Exception) {
                Toast.makeText(this@RegisterActivity, "Error loading routes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchStops(routeId: Any?) {
        val rId = routeId.toString()
        lifecycleScope.launch {
            try {
                // In this architecture, we filter stops by routeId
                val snapshot = db.collection("stops")
                    .whereEqualTo("routeId", rId)
                    .get().await()
                
                stopList.clear()
                val stopNames = mutableListOf<String>()
                
                for (doc in snapshot.documents) {
                    val stop = doc.toObject(Stop::class.java)
                    if (stop != null) {
                        stopList.add(stop)
                        stopNames.add(stop.name)
                    }
                }
                
                val adapter = ArrayAdapter(this@RegisterActivity, android.R.layout.simple_spinner_item, stopNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerStops.adapter = adapter
            } catch (e: Exception) {
                Toast.makeText(this@RegisterActivity, "Error loading stops", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeRegistrationFlow() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim().lowercase()
        val password = binding.etPassword.text.toString().trim()
        val functionalId = binding.etFunctionalId.text.toString().trim()
        val isStudent = binding.rbStudent.isChecked

        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || functionalId.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                binding.btnRegister.isEnabled = false
                binding.btnRegister.text = "Verifying Authorization..."
                
                val collection = if (isStudent) "students" else "drivers"
                val whitelistDoc = db.collection(collection).document(email).get().await()
                
                if (!whitelistDoc.exists()) {
                    throw Exception("Email not authorized. Please contact University Admin.")
                }
                
                if (whitelistDoc.getBoolean("isRegistered") == true) {
                    throw Exception("Account already registered. Please Login.")
                }

                binding.btnRegister.text = "Creating Account..."
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                val user = authResult.user ?: throw Exception("Authentication engine failure.")
                
                val adminData = whitelistDoc.data ?: emptyMap()
                val finalData = adminData.toMutableMap()
                
                finalData[if (isStudent) "studentUID" else "driverUID"] = user.uid
                finalData["isRegistered"] = true
                finalData["lastUpdated"] = Timestamp.now()

                if (isStudent) {
                    val uniPos = binding.spinnerUniversities.selectedItemPosition
                    val routePos = binding.spinnerRoutes.selectedItemPosition
                    val stopPos = binding.spinnerStops.selectedItemPosition

                    if (uniPos >= 0) finalData["universityId"] = universityList[uniPos].universityId
                    if (routePos >= 0) finalData["routeId"] = routeList[routePos].routeId
                    if (stopPos >= 0) {
                        val stop = stopList[stopPos]
                        finalData["pickupStation"] = stop.stopId
                        // Store the GeoPoint directly from the stop model
                        finalData["geoPoint"] = stop.geoPoint
                        finalData["latitude"] = stop.latitude
                        finalData["longitude"] = stop.longitude
                    }
                }
                
                db.collection(collection).document(user.uid).set(finalData).await()
                
                // Remove the whitelist document once claimed to prevent duplicate registrations
                db.collection(collection).document(email).delete().await()
                
                Toast.makeText(this@RegisterActivity, "Account Claimed Successfully", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                finishAffinity()

            } catch (e: Exception) {
                val rawMsg = e.message ?: ""
                val displayMsg = when {
                    rawMsg.contains("CONFIGURATION_NOT_FOUND") -> 
                        "Auth configuration error. Please ensure: 1. Email/Password is enabled in Firebase. 2. 'Identity Toolkit API' is enabled in Google Cloud Console for project '${FirebaseApp.getInstance().options.projectId}'."
                    rawMsg.contains("identitytoolkit") && rawMsg.contains("blocked") -> 
                        getString(R.string.error_auth_blocked)
                    else -> rawMsg.ifEmpty { "Registration failed. Please try again." }
                }
                Toast.makeText(this@RegisterActivity, displayMsg, Toast.LENGTH_LONG).show()
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Create Account"
            }
        }
    }
}
