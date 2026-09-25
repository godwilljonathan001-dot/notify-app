import os
import glob
import re

files = glob.glob("app/src/main/java/com/example/*.kt")
for file in files:
    with open(file, "r") as f:
        content = f.read()
    
    # ActivitybusesBinding -> ActivityBusesBinding
    content = re.sub(r'ActivitybusesBinding', 'ActivityBusesBinding', content)
    content = re.sub(r'ActivitydriversBinding', 'ActivityDriversBinding', content)
    content = re.sub(r'ActivityroutesBinding', 'ActivityRoutesBinding', content)
    content = re.sub(r'ActivitystopsBinding', 'ActivityStopsBinding', content)
    content = re.sub(r'ActivitystudentsBinding', 'ActivityStudentsBinding', content)
    
    content = re.sub(r'ActivityimportbusesBinding', 'ActivityImportBusesBinding', content)
    content = re.sub(r'ActivityimportdriversBinding', 'ActivityImportDriversBinding', content)
    content = re.sub(r'ActivityimportroutesBinding', 'ActivityImportRoutesBinding', content)
    content = re.sub(r'ActivityimportstopsBinding', 'ActivityImportStopsBinding', content)
    content = re.sub(r'ActivityimportstudentsBinding', 'ActivityImportStudentsBinding', content)

    content = re.sub(r'ActivityLiveTrackingBinding', 'ActivityLivetrackingBinding', content)
    
    with open(file, "w") as f:
        f.write(content)

print("Fixed bindings")
