import re

with open("app/src/main/res/layout/activity_admin.xml", "r") as f:
    admin_xml = f.read()

def extract_layout(id_name):
    # Find the start of the layout tag with this id
    pattern = r'(<[^>]+android:id="@+id/' + id_name + r'".*?)<!--'
    # Wait, simple regex might not work well for nested XML. Let's use simple string searching.
    return None

