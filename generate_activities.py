import os

activities = [
    "UniversityActivity",
    "LiveTrackingActivity",
    "ReportsActivity",
    "SettingsActivity",
    "ImportStudentsActivity",
    "ImportDriversActivity",
    "ImportBusesActivity",
    "ImportRoutesActivity",
    "ImportStopsActivity",
    "StudentsActivity",
    "DriversActivity",
    "RoutesActivity",
    "StopsActivity",
    "BusesActivity"
]

package_name = "com.example"
base_dir = "app/src/main/java/com/example"
res_dir = "app/src/main/res/layout"

for act in activities:
    # Create Activity KT file
    kt_path = os.path.join(base_dir, f"{act}.kt")
    if not os.path.exists(kt_path):
        with open(kt_path, "w") as f:
            f.write(f"""package {package_name}

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import {package_name}.databinding.Activity{act.replace('Activity', '')}Binding

class {act} : AppCompatActivity() {{
    private lateinit var binding: Activity{act.replace('Activity', '')}Binding

    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)
        binding = Activity{act.replace('Activity', '')}Binding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // TODO: Implement {act} logic
    }}
}}
""")

    # Create XML Layout
    layout_name = f"activity_{act.replace('Activity', '').lower()}.xml"
    xml_path = os.path.join(res_dir, layout_name)
    if not os.path.exists(xml_path):
        with open(xml_path, "w") as f:
            f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">
    
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="{act}"
        android:textSize="24sp"
        android:textStyle="bold" />
        
</LinearLayout>
""")

print("Generated Activities")
