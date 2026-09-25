import re

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

manifest_path = "app/src/main/AndroidManifest.xml"
with open(manifest_path, "r") as f:
    content = f.read()

activity_tags = ""
for act in activities:
    if f'android:name=".{act}"' not in content:
        activity_tags += f'        <activity android:name=".{act}" android:exported="false" android:theme="@style/Theme.MyApplication" />\n'

new_content = content.replace("</application>", activity_tags + "    </application>")

with open(manifest_path, "w") as f:
    f.write(new_content)

print("Updated Manifest")
