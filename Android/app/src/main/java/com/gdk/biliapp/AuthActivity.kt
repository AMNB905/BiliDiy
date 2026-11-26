package com.gdk.biliapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 邀请制登录页：登录 / 邀请码注册。
 * MainActivity 在未登录时会跳到这里；登录成功后回到主界面。
 */
class AuthActivity : BaseActivity() {

    private lateinit var pageRoot: View
    private lateinit var content: View
    private lateinit var tabLogin: TextView
    private lateinit var tabRegister: TextView
    private lateinit var panelLogin: View
    private lateinit var panelRegister: View
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etInvite: EditText
    private lateinit var etRegUser: EditText
    private lateinit var etRegPass: EditText
    private lateinit var etRegNick: EditText
    private lateinit var btnLogin: TextView
    private lateinit var btnRegister: TextView
    private lateinit var tvLoginError: TextView
    private lateinit var tvRegError: TextView
    private lateinit var tvAuthHint: TextView
    private lateinit var tvDevice: TextView

    private var gate = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        if (!Security.ensure(this)) return
        Account.init(this)
        Cloud.init(this)
        Cloud.refresh(this)
        gate = intent.getBooleanExtra("gate", false)

        // 登录门禁进入时，系统返回键直接退出整个 App；普通进入时保持默认返回
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gate) {
                    finishAffinity()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (Account.isLoggedIn(this)) {
            goNext()
            return
        }

        Ui.edgeToEdge(this, Ui.isLight(this))
        setContentView(R.layout.activity_auth)
        pageRoot = findViewById(R.id.pageRoot)
        content = findViewById(R.id.content)
        tabLogin = findViewById(R.id.tabLogin)
        tabRegister = findViewById(R.id.tabRegister)
        panelLogin = findViewById(R.id.panelLogin)
        panelRegister = findViewById(R.id.panelRegister)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etInvite = findViewById(R.id.etInvite)
        etRegUser = findViewById(R.id.etRegUser)
        etRegPass = findViewById(R.id.etRegPass)
        etRegNick = findViewById(R.id.etRegNick)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        tvLoginError = findViewById(R.id.tvLoginError)
        tvRegError = findViewById(R.id.tvRegError)
        tvAuthHint = findViewById(R.id.tvAuthHint)
        tvDevice = findViewById(R.id.tvDevice)

        tvDevice.text = "设备 ID · " + Profile.deviceId(this)
        findViewById<TextView>(R.id.btnExit).setOnClickListener { finishAffinity() }
        tabLogin.setOnClickListener { showTab(false) }
        tabRegister.setOnClickListener { showTab(true) }
        btnLogin.setOnClickListener { doLogin() }
        btnRegister.setOnClickListener { doRegister() }
        etPass.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                doLogin(); true
            } else false
        }

        showTab(false)
        applyInsets()
        Cloud.gate(this)
    }

    override fun onResume() {
        super.onResume()
        Cloud.gate(this)
    }

    private fun showTab(register: Boolean) {
        panelLogin.visibility = if (register) View.GONE else View.VISIBLE
        panelRegister.visibility = if (register) View.VISIBLE else View.GONE
        tabLogin.setBackgroundResource(if (register) R.drawable.bg_chip else R.drawable.bg_chip_on)
        tabRegister.setBackgroundResource(if (register) R.drawable.bg_chip_on else R.drawable.bg_chip)
        tabLogin.setTextColor(ContextCompat.getColor(this, if (register) R.color.textSecondary else R.color.pink))
        tabRegister.setTextColor(ContextCompat.getColor(this, if (register) R.color.pink else R.color.textSecondary))
        tvAuthHint.text = if (register) {
            "注册需要管理员在后台生成的邀请码"
        } else {
            "还没有账号？邀请码注册入口在右侧"
        }
        hideKeyboard()
    }

    private fun doLogin() {
        if (busy) return
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        tvLoginError.visibility = View.GONE
        if (user.isEmpty() || pass.isEmpty()) {
            showLoginError("请输入账号和密码")
            return
        }
        setBusy(true, "登录中…")
        Account.login(this, user, pass) { ok, msg, _ ->
            setBusy(false, "登录")
            if (isFinishing || isDestroyed) return@login
            if (ok) {
                Ui.toast(this, "登录成功")
                goNext()
            } else {
                showLoginError(msg)
            }
        }
    }

    private fun doRegister() {
        if (busy) return
        val invite = etInvite.text.toString().trim().uppercase()
        val user = etRegUser.text.toString().trim()
        val pass = etRegPass.text.toString()
        val nick = etRegNick.text.toString().trim()
        tvRegError.visibility = View.GONE
        if (invite.isEmpty()) {
            showRegError("请输入邀请码")
            return
        }
        if (user.length < 3) {
            showRegError("用户名至少 3 位")
            return
        }
        if (pass.length < 8) {
            showRegError("密码至少 8 位")
            return
        }
        setBusy(true, "注册中…")
        Account.register(this, invite, user, pass, nick) { ok, msg, _ ->
            setBusy(false, "注册并登录")
            if (isFinishing || isDestroyed) return@register
            if (ok) {
                Ui.toast(this, "注册成功")
                goNext()
            } else {
                showRegError(msg)
            }
        }
    }

    private fun goNext() {
        Cloud.refresh(this, force = true)
        if (gate) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        } else {
            finish()
        }
    }

    private fun showLoginError(msg: String) {
        tvLoginError.text = msg
        tvLoginError.visibility = View.VISIBLE
    }

    private fun showRegError(msg: String) {
        tvRegError.text = msg
        tvRegError.visibility = View.VISIBLE
    }

    private fun setBusy(value: Boolean, label: String) {
        busy = value
        btnLogin.isEnabled = !value
        btnRegister.isEnabled = !value
        btnLogin.text = if (value) "请稍候…" else "登录"
        btnRegister.text = if (value) "请稍候…" else "注册并登录"
        if (!value) {
            btnLogin.text = "登录"
            btnRegister.text = "注册并登录"
        }
        tvAuthHint.text = label
        if (value) hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken ?: pageRoot.windowToken, 0)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(pageRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
            content.setPadding(
                Ui.dp(this, 24), Ui.dp(this, 28),
                Ui.dp(this, 24), Ui.dp(this, 28) + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(pageRoot)
    }
}
