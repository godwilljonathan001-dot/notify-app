import re

with open("app/src/main/java/com/example/LoginActivity.kt", "r") as f:
    content = f.read()

# Replace doc.id with doc.getString("adminId") or studentId, etc.
# Wait, actually let's just write a new signInUser function.

new_sign_in = """
    private suspend fun signInUser(input: String, password: String) {
        var loginEmail = input.trim()
        var finalRole = "student"
        var businessId = ""

        // Frictionless auto-creation for default test accounts
        if (loginEmail == "admin@gusa.com" || loginEmail == "driver@gusa.com" || loginEmail == "student@gusa.com") {
            setupTestAccounts()
        }

        // 1. Resolve custom ID (S101, D101, etc.) or Email address
        if (!loginEmail.contains("@")) {
            val adminQuery = db.collection("admins").whereEqualTo("adminId", loginEmail).get().await()
            if (!adminQuery.isEmpty) {
                val doc = adminQuery.documents[0]
                loginEmail = doc.getString("email") ?: throw Exception("Admin email not found in Firestore")
                finalRole = "admin"
                businessId = doc.getString("adminId") ?: doc.id
            } else {
                val studentQuery = db.collection("students").whereEqualTo("studentId", loginEmail).get().await()
                if (!studentQuery.isEmpty) {
                    val doc = studentQuery.documents[0]
                    loginEmail = doc.getString("email") ?: throw Exception("Student email not found in Firestore")
                    finalRole = "student"
                    businessId = doc.getString("studentId") ?: doc.id
                } else {
                    val driverQuery = db.collection("drivers").whereEqualTo("driverId", loginEmail).get().await()
                    if (!driverQuery.isEmpty) {
                        val doc = driverQuery.documents[0]
                        loginEmail = doc.getString("email") ?: throw Exception("Driver email not found in Firestore")
                        finalRole = "driver"
                        businessId = doc.getString("driverId") ?: doc.id
                    } else {
                        throw Exception("No user found with ID '$loginEmail'")
                    }
                }
            }
        }

        // 2. Perform FirebaseAuth Sign In
        try {
            auth.signInWithEmailAndPassword(loginEmail, password).await()
        } catch (e: Exception) {
            var exists = false
            if (!db.collection("admins").whereEqualTo("email", loginEmail).get().await().isEmpty) exists = true
            if (!db.collection("drivers").whereEqualTo("email", loginEmail).get().await().isEmpty) exists = true
            if (!db.collection("students").whereEqualTo("email", loginEmail).get().await().isEmpty) exists = true
            
            if (exists) {
                try {
                    auth.createUserWithEmailAndPassword(loginEmail, password).await()
                } catch (ex: Exception) {
                    throw Exception("Login failed: ${e.message}")
                }
            } else {
                throw e
            }
        }

        // 3. Resolve role & business ID if logged in via Email directly
        if (input.contains("@")) {
            val adminQuery = db.collection("admins").whereEqualTo("email", loginEmail).get().await()
            if (!adminQuery.isEmpty) {
                finalRole = "admin"
                val doc = adminQuery.documents[0]
                businessId = doc.getString("adminId") ?: doc.id
            } else {
                val studentQuery = db.collection("students").whereEqualTo("email", loginEmail).get().await()
                if (!studentQuery.isEmpty) {
                    finalRole = "student"
                    val doc = studentQuery.documents[0]
                    businessId = doc.getString("studentId") ?: doc.id
                } else {
                    val driverQuery = db.collection("drivers").whereEqualTo("email", loginEmail).get().await()
                    if (!driverQuery.isEmpty) {
                        finalRole = "driver"
                        val doc = driverQuery.documents[0]
                        businessId = doc.getString("driverId") ?: doc.id
                    } else {
                        // Fallback
                        if (loginEmail == "admin@gusa.com" || loginEmail == "admin@notify.com") {
                            finalRole = "admin"
                            businessId = "admin_uid"
                        } else {
                            throw Exception("User registered in Auth but not mapped to any Firestore document.")
                        }
                    }
                }
            }
        }

        navigateToDashboard(finalRole, businessId)
    }
"""

start_idx = content.find("private suspend fun signInUser")
end_idx = content.find("private fun navigateToDashboard")

if start_idx != -1 and end_idx != -1:
    new_content = content[:start_idx] + new_sign_in + content[end_idx:]
    with open("app/src/main/java/com/example/LoginActivity.kt", "w") as f:
        f.write(new_content)
    print("Updated LoginActivity")
else:
    print("Could not find function bounds")
