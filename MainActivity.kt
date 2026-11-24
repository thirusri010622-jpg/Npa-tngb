package com.example.recoveryfield

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import com.example.recoveryfield.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private val Navy = Color(0xFF075DA8)
private val Blue = Color(0xFF1068B2)
private val Green = Color(0xFF159447)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        setContent { RecoveryFieldApp(this) }
    }
}

@Composable
private fun RecoveryFieldApp(activity: MainActivity) {
    val db = remember { AppDb.get(activity) }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var range by remember { mutableStateOf<Pair<Double, Double?>?>(null) }
    var initialSearch by remember { mutableStateOf("") }
    var srNo by remember { mutableStateOf(activity.getPreferences(0).getString("srNo", "") ?: "") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            activity.getPreferences(0).edit().putString("uri", uri.toString()).apply()
            status = "Importing Excel…"
            try {
                val n = withContext(Dispatchers.IO) {
                    ExcelStore.import(activity, uri, db)
                }
                refresh++
                status = "$n accounts imported successfully"
                screen = 1
            } catch (e: Exception) { status = "Import failed: ${e.message ?: "Unknown error"}" }
        }
    }

    val openMembers = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            status = "Importing Member details…"
            try {
                val n = withContext(Dispatchers.IO) { ExcelStore.importMembers(activity, uri, db) }
                refresh++
                status = "$n member records imported successfully"
            } catch (e: Exception) { status = "Member import failed: ${e.message ?: "Unknown error"}" }
        }
    }

    val save: () -> Unit = {
        scope.launch {
            val source = activity.getPreferences(0).getString("uri", null)
            if (source == null) {
                status = "Import an Excel file first"
            } else if (srNo.length != 5) {
                status = "Enter a valid 5-digit SR No first"
            } else try {
                status = "Saving new follow-ups to Follow up…"
                val exported = withContext(Dispatchers.IO) {
                    ExcelStore.export(activity, Uri.parse(source), db, srNo)
                }
                status = if (exported == 0) "No new follow-ups to export"
                else "$exported new follow-up(s) saved to Follow up • SR No: $srNo"
            } catch (e: Exception) { status = "Save failed: ${e.message ?: "Unknown error"}" }
        }
        Unit
    }

    BackHandler(enabled = screen != 0 || selectedId != null) {
        when {
            selectedId != null -> selectedId = null
            screen == 1 -> screen = 0
            else -> screen = 1
        }
    }

    if (selectedId != null) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Green)) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                AccountScreen(db, selectedId!!, onBack = { selectedId = null }, onSaved = { refresh++ })
            }
        }
        return
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Green)) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            when (screen) {
            0 -> WelcomeScreen(srNo = srNo, onSrNoChanged = { srNo = it }, onStart = { entered ->
                srNo = entered
                activity.getPreferences(0).edit().putString("srNo", entered).apply()
                screen = 1
            })
            1 -> HomeScreen(
                onSearch = { range = null; screen = 2 },
                onFollowUps = { screen = 3 },
                onAnalysis = { screen = 4 },
                onImport = { open.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) },
                onImportMembers = { openMembers.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) },
                onExport = save,
                status = status
            )
            2 -> AccountsScreen(db, refresh, initialSearch, range, onBack = { screen = 1 }, onSearchChanged = { initialSearch = it }, onAccount = { selectedId = it })
            3 -> FollowUpListScreen(db, refresh, onBack = { screen = 1 }, onAccount = { selectedId = it })
            else -> AnalysisScreen(db, refresh, onBack = { screen = 1 }) { r -> range = r; screen = 2 }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Surface(color = Navy, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            ImageLogo(Modifier.size(38.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("தமிழ்நாடு கிராம வங்கி", color = Color.White, fontSize = 11.sp)
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            action?.invoke()
        }
    }
}

@Composable
private fun ImageLogo(modifier: Modifier = Modifier) {
    Image(painterResource(com.example.recoveryfield.R.drawable.tngb_logo), contentDescription = "Tamilnadu Grama Bank logo", modifier = modifier)
}

@Composable
private fun WelcomeScreen(srNo: String, onSrNoChanged: (String) -> Unit, onStart: (String) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(35.dp))
                ImageLogo(Modifier.size(105.dp))
                Text("தமிழ்நாடு கிராம வங்கி", color = Navy, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("TAMILNADU GRAMA BANK", color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("People's Bank  •  Rural Prosperity", color = Blue, fontSize = 12.sp)
                Spacer(Modifier.height(48.dp))
                Text("NPA Management", color = Color(0xFF124A8D), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Recover  •  Restore  •  Grow", color = Navy, fontSize = 17.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(
                    value = srNo,
                    onValueChange = { onSrNoChanged(it.filter(Char::isDigit).take(5)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SR No") },
                    placeholder = { Text("Enter 5-digit SR No") },
                    leadingIcon = { Icon(Icons.Default.Badge, null, tint = Blue) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Enter exactly 5 digits") }
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = { onStart(srNo) }, enabled = srNo.length == 5, modifier = Modifier.fillMaxWidth(0.82f).height(52.dp), shape = RoundedCornerShape(28.dp)) {
                    Text("Get Started  →", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Text("Small Steps  Big Recovery", color = Green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HomeScreen(onSearch: () -> Unit, onFollowUps: () -> Unit, onAnalysis: () -> Unit, onImport: () -> Unit, onImportMembers: () -> Unit, onExport: () -> Unit, status: String) {
    Scaffold(topBar = { AppHeader("TAMILNADU GRAMA BANK", action = { Icon(Icons.Default.Notifications, null, tint = Color.White) }) }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Good Morning,", color = Navy, fontSize = 15.sp)
                Text("Recovery Team", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Together for a stronger tomorrow", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { HomeTileGrid(onSearch, onFollowUps, onAnalysis, onImport, onImportMembers, onExport) }
            if (status.isNotBlank()) item { Text(status, color = Green, fontSize = 13.sp) }
            item { Text("Small Steps  Big Recovery", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
        }
    }
}

@Composable
private fun HomeTileGrid(onSearch: () -> Unit, onFollowUps: () -> Unit, onAnalysis: () -> Unit, onImport: () -> Unit, onImportMembers: () -> Unit, onExport: () -> Unit) {
    val tiles = listOf(
        Triple("Search / View\nAccounts", Icons.Default.Search, onSearch),
        Triple("Follow Up &\nUpdate", Icons.Default.Event, onFollowUps),
        Triple("Analysis &\nReports", Icons.Default.BarChart, onAnalysis),
        Triple("Import / Export\n(Excel)", Icons.Default.Description, onImport),
        Triple("Import Member\nDetails", Icons.Default.People, onImportMembers),
        Triple("Export Data\n(Excel)", Icons.Default.Download, onExport),
        Triple("About", Icons.Default.Info) { }
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, icon, click) ->
                    Card(Modifier.weight(1f).height(112.dp).clickable { click() }, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon, null, tint = if (label.contains("Follow")) Green else Blue, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(6.dp)); Text(label, color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AccountsScreen(db: AppDb, refresh: Int, initialSearch: String, range: Pair<Double, Double?>?, onBack: () -> Unit, onSearchChanged: (String) -> Unit, onAccount: (Long) -> Unit) {
    var q by remember(initialSearch) { mutableStateOf(initialSearch) }
    var branch by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf(listOf<String>()) }
    var rows by remember { mutableStateOf(listOf<AccountEntity>()) }
    var page by remember { mutableIntStateOf(0) }
    val pageSize = 100

    LaunchedEffect(refresh) { branches = db.accountDao().branches(); page = 0 }
    LaunchedEffect(page, q, branch, range) {
        rows = db.accountDao().paged(q.trim(), branch, range?.first ?: 0.0, range?.second, "name", 0, pageSize, page * pageSize)
    }

    Scaffold(topBar = { AppHeader("Search Accounts", onBack = onBack) }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it; page = 0; onSearchChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Account No / CIF / Borrower Name") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (q.isNotEmpty()) {
                            IconButton(onClick = { q = ""; page = 0; onSearchChanged("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                )
            }
            item { DropdownField("Branch", branch.ifBlank { "All Branches" }, branches) { branch = if (it == "All Branches") "" else it; page = 0 } }
            items(rows, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth().clickable { onAccount(a.id) }) {
                    Column(Modifier.padding(13.dp)) {
                        Text(a.name.ifBlank { "Unnamed borrower" }, color = Navy, fontWeight = FontWeight.Bold)
                        Text("${a.accountNo} • ${a.branch}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${money(a.balance)} • NPA ${a.npaDate}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { if (rows.size == pageSize) Button(onClick = { page++ }, Modifier.fillMaxWidth()) { Text("Load more accounts") } else if (rows.isEmpty()) Text("No accounts found", Modifier.padding(20.dp)) }
        }
    }
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = Navy)
        Box {
            OutlinedTextField(value, {}, Modifier.fillMaxWidth(), readOnly = true, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            Spacer(Modifier.matchParentSize().clickable { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false }) }
            }
        }
    }
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.CalendarMonth, null) }
        )
        Spacer(Modifier.matchParentSize().clickable {
            val c = Calendar.getInstance()
            DatePickerDialog(context, { _, y, m, d ->
                onChange(String.format(Locale.US, "%02d-%02d-%04d", d, m + 1, y))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        })
    }
}

@Composable
private fun FollowUpListScreen(db: AppDb, refresh: Int, onBack: () -> Unit, onAccount: (Long) -> Unit) {
    var rows by remember { mutableStateOf(listOf<FollowUpEntity>()) }
    var accounts by remember { mutableStateOf(mapOf<Long, AccountEntity>()) }
    val today = todayString()
    LaunchedEffect(refresh) {
        rows = db.followUpDao().due(today)
        accounts = rows.mapNotNull { f -> db.accountDao().get(f.accountId)?.let { f.accountId to it } }.toMap()
    }
    Scaffold(topBar = { AppHeader("Follow Up & Update", onBack = onBack) }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Today - ${displayDate(today)}", color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatBox("Total", rows.size.toString(), Modifier.weight(1f)); StatBox("Followed", rows.count { it.remarks.contains("followed", true) }.toString(), Modifier.weight(1f)); StatBox("Pending", rows.count { !it.remarks.contains("followed", true) }.toString(), Modifier.weight(1f)) } }
            if (rows.isEmpty()) item { Text("No follow-ups scheduled for today.", Modifier.padding(20.dp)) }
            items(rows, key = { it.id }) { f ->
                val a = accounts[f.accountId]
                Card(Modifier.fillMaxWidth().clickable { onAccount(f.accountId) }) { Column(Modifier.padding(13.dp)) {
                    Text(a?.accountNo ?: "Account", color = Navy, fontWeight = FontWeight.Bold)
                    Text(a?.name ?: "", fontWeight = FontWeight.SemiBold)
                    Text(a?.branch ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${f.mode} • ${f.nextDate}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}

@Composable private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) { Card(modifier) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = Navy, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(label, fontSize = 11.sp) } } }

@Composable
private fun AnalysisScreen(db: AppDb, refresh: Int, onBack: () -> Unit, onRange: (Pair<Double, Double?>) -> Unit) {
    var buckets by remember { mutableStateOf(listOf<BalanceBucket>()) }
    var total by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(refresh) { buckets = db.accountDao().balanceBuckets(); total = db.accountDao().totalBalance() }
    Scaffold(topBar = { AppHeader("Analysis & Reports", onBack = onBack) }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Balance Bucket Wise Analysis", color = Navy, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Total outstanding: ${money(total)}", color = Blue) }
            items(buckets) { b -> Card(Modifier.fillMaxWidth().clickable { onRange(b.min to b.max) }) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(b.label, fontWeight = FontWeight.SemiBold); Text("${b.accountCount} accounts", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(money(b.totalBalance), color = Navy, fontWeight = FontWeight.Bold) } } }
        }
    }
}

@Composable
private fun AccountScreen(db: AppDb, accountId: Long, onBack: () -> Unit, onSaved: () -> Unit) {
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var followups by remember { mutableStateOf(listOf<FollowUpEntity>()) }
    var members by remember { mutableStateOf(listOf<MemberEntity>()) }
    var showMembers by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(accountId) {
        val loadedAccount = db.accountDao().get(accountId)
        account = loadedAccount
        followups = db.followUpDao().forAccount(accountId)
        loadedAccount?.let { members = db.memberDao().forLoanAccount(canonicalAccountKey(it.accountNo)) }
    }
    val a = account ?: return
    Scaffold(topBar = { AppHeader("Account Details", onBack = onBack) }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                DetailLine("Account No", a.accountNo); DetailLine("CIF", a.cif); DetailLine("Borrower Name", a.name); DetailLine("Branch", a.branch); DetailLine("Product", a.product); DetailLine("Limit", a.extraJsonLimit()); DetailLine("Outstanding", money(a.balance)); DetailLine("Account Approval Date", a.approvalDate); DetailLine("NPA Date", a.npaDate); DetailLine("Category", a.category); DetailLine("NGO", a.ngo)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { showMembers = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.People, null); Spacer(Modifier.width(6.dp))
                    Text(if (members.isEmpty()) "View Members" else "View Members (${members.size})")
                }
                Spacer(Modifier.height(10.dp)); Button(onClick = { add = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Follow Up & Update") }
                Spacer(Modifier.height(10.dp)); Text("Follow-up History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            items(followups) { f -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("${f.date} • ${f.mode}", fontWeight = FontWeight.SemiBold); if (f.personMet.isNotBlank()) Text("Person met: ${f.personMet}"); if (f.discussion.isNotBlank()) Text(f.discussion); Text("Commitment: ${money(f.commitment)} • Next: ${f.nextDate}"); if (f.remarks.isNotBlank()) Text(f.remarks) } } }
        }
    }
    if (showMembers) MemberDetailsDialog(members, onDismiss = { showMembers = false })
    if (add) FollowupDialog(a, db, onCancel = { add = false }) { add = false; scope.launch { followups = db.followUpDao().forAccount(accountId); onSaved() } }
}

@Composable
private fun MemberDetailsDialog(members: List<MemberEntity>, onDismiss: () -> Unit) {
    val total = members.sumOf { it.sbBalance }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group Members") },
        text = {
            if (members.isEmpty()) {
                Text("No member details found for this Loan Account No.")
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                items(members, key = { it.id }) { member ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(member.memberName.ifBlank { "Unnamed member" }, fontWeight = FontWeight.Bold, color = Navy)
                            if (member.sbAccountNo.isNotBlank()) Text("SB A/c: ${member.sbAccountNo}")
                            Text("SB Balance: ${money(member.sbBalance)}")
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Total SB Balance: ${money(total)}", fontWeight = FontWeight.Bold, color = Navy)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun AccountEntity.extraJsonLimit(): String = try { org.json.JSONObject(extraJson).optString("limit", "—") } catch (_: Exception) { "—" }

@Composable private fun DetailLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text("$label: ", fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.width(150.dp)); Text(value.ifBlank { "—" }) } }

@Composable
private fun FollowupDialog(account: AccountEntity, db: AppDb, onCancel: () -> Unit, onSaved: () -> Unit) {
    var date by remember { mutableStateOf(todayString()) }; var mode by remember { mutableStateOf("Visit") }; var person by remember { mutableStateOf("") }; var commitment by remember { mutableStateOf("") }; var next by remember { mutableStateOf(todayString()) }; var remarks by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    AlertDialog(onDismissRequest = onCancel, title = { Text("Add Follow-up") }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DateField("Follow-up date", date) { date = it } }; item { OutlinedTextField(mode, { mode = it }, Modifier.fillMaxWidth(), label = { Text("Mode") }, singleLine = true) }; item { OutlinedTextField(person, { person = it }, Modifier.fillMaxWidth(), label = { Text("Person met") }, singleLine = true) }; item { OutlinedTextField(commitment, { commitment = it }, Modifier.fillMaxWidth(), label = { Text("Commitment amount") }, singleLine = true) }; item { DateField("Next follow-up date", next) { next = it } }; item { OutlinedTextField(remarks, { remarks = it }, Modifier.fillMaxWidth(), label = { Text("Remarks") }, minLines = 2) }
    } }, confirmButton = { Button(onClick = { scope.launch { db.followUpDao().insert(FollowUpEntity(accountId = account.id, date = date, mode = mode, personMet = person, discussion = "", commitment = commitment.toDoubleOrNull() ?: 0.0, nextDate = next, remarks = remarks)); scheduleReminder(context); onSaved() } }) { Text("Save") } }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

private fun canonicalAccountKey(value: String): String = value.trim().replace(",", "").replace(" ", "").replace(Regex("\\.0+$"), "")

private fun todayString() = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
private fun displayDate(v: String): String {
    if (v.isBlank()) return v
    return try {
        if (Regex("\\d{2}-\\d{2}-\\d{4}").matches(v)) v
        else SimpleDateFormat("dd-MM-yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(v)!!)
    } catch (_: Exception) { v }
}
private fun money(v: Double) = "₹" + String.format(Locale.US, "%,.0f", v)
