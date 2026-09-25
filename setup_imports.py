import os

imports = ["Students", "Drivers", "Buses", "Routes", "Stops"]

for name in imports:
    with open(f"app/src/main/java/com/example/Import{name}Activity.kt", "w") as f:
        f.write(f"""package com.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityImport{name.lower()}Binding

class Import{name}Activity : AppCompatActivity() {{
    private lateinit var binding: ActivityImport{name.lower()}Binding

    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)
        binding = ActivityImport{name.lower()}Binding.inflate(layoutInflater)
        setContentView(binding.root)
    }}
}}
""")

for name in imports:
    with open(f"app/src/main/java/com/example/{name}Activity.kt", "w") as f:
        f.write(f"""package com.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.Activity{name.lower()}Binding

class {name}Activity : AppCompatActivity() {{
    private lateinit var binding: Activity{name.lower()}Binding

    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)
        binding = Activity{name.lower()}Binding.inflate(layoutInflater)
        setContentView(binding.root)
    }}
}}
""")
