package com.example.recoveryfield

import android.content.Context
import android.net.Uri
import com.example.recoveryfield.data.AccountEntity
import com.example.recoveryfield.data.MemberEntity
import com.example.recoveryfield.data.AppDb
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExcelStore {
    private val formatter = DataFormatter(Locale.US)

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.US)
        .replace("&", "and")
        .replace("/", " ")
        .replace("\\", " ")
        .replace("_", " ")
        .replace("-", " ")
        .replace(".", " ")
        .replace("(", " ")
        .replace(")", " ")
        .replace(" ", "")

    private fun canonicalAccountKey(value: String): String = value.trim()
        .replace(",", "")
        .replace(" ", "")
        .replace(Regex("\\.0+$"), "")

    private fun cellString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) {
                SimpleDateFormat("dd-MM-yyyy", Locale.US).format(cell.dateCellValue)
            } else formatter.formatCellValue(cell).trim()
            else -> formatter.formatCellValue(cell).trim()
        }
    }

    private fun normalizeDate(value: String): String {
        val v = value.trim()
        if (v.isBlank()) return ""
        val patterns = listOf("dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "yyyy/MM/dd", "dd.MM.yyyy")
        for (pattern in patterns) {
            try {
                val input = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val parsed = input.parse(v) ?: continue
                return SimpleDateFormat("dd-MM-yyyy", Locale.US).format(parsed)
            } catch (_: Exception) { }
        }
        return v
    }

    private fun money(value: String): Double {
        val cleaned = value
            .replace(",", "")
            .replace("₹", "")
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
            .trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun stableId(branch: String, account: String, name: String): Long {
        val key = if (account.isNotBlank()) "$branch|$account" else "$branch|$name"
        var hash = -3750763034362895579L
        for (b in key.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (b.toLong() and 0xffL)) * 1099511628211L
        }
        return if (hash == 0L) 1L else hash
    }

    suspend fun import(context: Context, uri: Uri, db: AppDb): Int {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open Excel file")
        val bytes = input.use { it.readBytes() }
        val wb = WorkbookFactory.create(ByteArrayInputStream(bytes))
        try {
            val sheet = wb.getSheet("NPA Data")
                ?: (0 until wb.numberOfSheets).map { wb.getSheetAt(it) }
                    .firstOrNull { it.sheetName.trim().startsWith("NPA", ignoreCase = true) }
                ?: wb.getSheetAt(0)

            val header = sheet.getRow(0) ?: return 0
            val map = mutableMapOf<String, Int>()
            for (cell in header) {
                val text = cellString(cell)
                if (text.isNotBlank()) map[normalize(text)] = cell.columnIndex
            }

            fun get(row: Row, vararg names: String): String {
                for (name in names) {
                    val index = map[normalize(name)] ?: continue
                    val value = cellString(row.getCell(index))
                    if (value.isNotBlank()) return value
                }
                return ""
            }

            val accounts = ArrayList<AccountEntity>(1000)
            var imported = 0
            db.accountDao().clear()

            suspend fun flushAccounts() {
                if (accounts.isNotEmpty()) {
                    db.accountDao().insertAll(accounts.toList())
                    imported += accounts.size
                    accounts.clear()
                }
            }

            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val name = get(row, "borrower name", "borrowername", "borrower_name", "borrower", "name", "customer name")
                val account = canonicalAccountKey(get(row, "account no", "account number", "account_no", "acct no", "acct_no", "a/c no", "a/c number", "acc no", "account"))
                if (name.isBlank() && account.isBlank()) continue

                val branch = get(row, "branch", "branch name", "branch_name")
                val npaDate = normalizeDate(get(row, "npa date", "npa_date", "npa dt", "npa_dt", "date of npa", "dt of npa"))
                val approvalDate = normalizeDate(get(row, "account approval date", "approval date", "account approved date", "date of approval", "sanction date", "APPRV_DATE", "loan date", "loan_date"))
                accounts += AccountEntity(
                    id = stableId(branch, account, name),
                    branch = branch,
                    accountNo = account,
                    cif = get(row, "cif", "cif no", "cif_no"),
                    name = name,
                    product = get(row, "product", "product code", "product_code"),
                    npaDate = npaDate,
                    approvalDate = approvalDate,
                    balance = money(get(row, "bal o/s", "bal os", "bal_os", "balance o/s", "balance os", "balance", "outstanding", "bal o/s amount")),
                    ngo = get(row, "ngo"),
                    category = get(row, "npa cat", "npa_cat", "npa cat.", "cat", "category"),
                    extraJson = org.json.JSONObject().put(
                        "limit", get(row, "LIMIT", "APP_AMT", "APP AMT", "loan amount", "loan_amount", "loan amt", "loan_amt")
                    ).toString()
                )
                if (accounts.size >= 1000) flushAccounts()
            }
            flushAccounts()



            return imported
        } finally {
            wb.close()
        }
    }

    suspend fun importMembers(context: Context, uri: Uri, db: AppDb): Int {
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open Member Excel file")
        val bytes = input.use { it.readBytes() }
        val wb = WorkbookFactory.create(ByteArrayInputStream(bytes))
        try {
            val sheet = (0 until wb.numberOfSheets)
                .map { wb.getSheetAt(it) }
                .firstOrNull { normalize(it.sheetName) in setOf("memberdetails", "members", "member") }
                ?: wb.getSheetAt(0)

            val header = sheet.getRow(0) ?: error("Member Excel has no header row")
            val map = mutableMapOf<String, Int>()
            for (cell in header) {
                val text = cellString(cell)
                if (text.isNotBlank()) map[normalize(text)] = cell.columnIndex
            }

            fun get(row: Row, vararg names: String): String {
                for (name in names) {
                    val index = map[normalize(name)] ?: continue
                    val value = cellString(row.getCell(index))
                    if (value.isNotBlank()) return value
                }
                return ""
            }

            val loanHeaders = arrayOf(
                "Loan Account No.", "Loan Account No", "Loan Account Number", "Loan Account",
                "Loan A/c No.", "Loan A/c No", "Loan A/c Number", "Loan No.", "Loan No", "Loan Number",
                "Account No.", "Account No", "Account Number", "A/c No.", "A/c No", "A/c Number",
                "Group A/c No", "Group A/c No.", "Group Account No", "Group Account Number"
            )
            val memberNameHeaders = arrayOf(
                "Linked Member name", "Linked Member Name", "Linked Member", "Member Name", "Member name",
                "Member", "Member Full Name", "Member's Name", "Name of Member", "Borrower Name", "Member/Name"
            )
            val sbAccountHeaders = arrayOf(
                "Linked SB A/c No.", "Member SB account No", "Linked SB Acc", "Member SB ac", "Linked SB",
                "Member SB A/c No", "Member SB A/c No.", "Member SB Account", "Member SB Account No.",
                "SB Account No", "SB Account No.", "SB A/c No", "SB A/c No.", "SB A/c Number",
                "SB Account Number", "Member SB No", "Member SB Number", "Linked SB Account",
                "Linked SB Account No", "Linked SB A/c", "Linked SB A/c No"
            )
            val sbBalanceHeaders = arrayOf(
                "SB Bal", "Linked SB bal", "SB balance", "Linked SB balance", "SB Bal.", "SB A/c Balance",
                "SB Account Balance", "Member SB Balance", "Member SB Bal", "Member SB Bal.",
                "Member SB A/c Balance", "Linked SB Balance", "Linked SB Bal", "Linked SB Bal.",
                "Savings Balance", "Savings A/c Balance"
            )

            loanHeaders.firstOrNull { map.containsKey(normalize(it)) }
                ?: error("Loan account header not found in Member Excel")
            memberNameHeaders.firstOrNull { map.containsKey(normalize(it)) }
                ?: error("Member name header not found in Member Excel")
            sbAccountHeaders.firstOrNull { map.containsKey(normalize(it)) }
                ?: error("Member SB account header not found in Member Excel")

            db.memberDao().clear()
            val members = ArrayList<MemberEntity>(1000)
            var imported = 0
            suspend fun flush() {
                if (members.isNotEmpty()) {
                    db.memberDao().insertAll(members.toList())
                    imported += members.size
                    members.clear()
                }
            }

            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val loan = canonicalAccountKey(get(row, *loanHeaders))
                val name = get(row, *memberNameHeaders).trim()
                val sb = get(row, *sbAccountHeaders).trim()
                val bal = money(get(row, *sbBalanceHeaders))
                if (loan.isBlank() && name.isBlank() && sb.isBlank()) continue
                members += MemberEntity(loanAccountNo = loan, memberName = name, sbAccountNo = sb, sbBalance = bal)
                if (members.size >= 1000) flush()
            }
            flush()
            return imported
        } finally {
            wb.close()
        }
    }

    suspend fun export(context: Context, uri: Uri, db: AppDb, srNo: String): Int {
        val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open the imported Excel file")
        val wb = WorkbookFactory.create(ByteArrayInputStream(original))
        try {
            val sh = wb.getSheet("Follow up") ?: wb.createSheet("Follow up")
            val standardHeaders = listOf(
                "SR No", "Created At", "Branch", "Account No", "Borrower Name", "Date",
                "Mode", "Person Met", "Discussion", "Commitment", "Next Follow-up", "Remarks"
            )

            fun textOf(cell: Cell?): String = cellString(cell)
            var srColumn = -1
            val header = sh.getRow(0)
            if (header != null) {
                for (cell in header) {
                    if (normalize(textOf(cell)) == "srno") {
                        srColumn = cell.columnIndex
                        break
                    }
                }
            }

            val hasHeaderContent = header != null && header.any { textOf(it).isNotBlank() }
            if (!hasHeaderContent) {
                val h = sh.getRow(0) ?: sh.createRow(0)
                standardHeaders.forEachIndexed { index, value -> h.createCell(index).setCellValue(value) }
                srColumn = 0
            } else if (srColumn < 0) {
                // Preserve all existing Follow up columns/data. Add SR No as a new column.
                srColumn = header!!.lastCellNum.toInt().coerceAtLeast(0)
                header.createCell(srColumn).setCellValue("SR No")
            }

            val followups = db.followUpDao().unexported()
            if (followups.isEmpty()) return 0
            val accounts = followups.mapNotNull { db.accountDao().get(it.accountId) }
                .associateBy { it.id }

            val createdFormat = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US)
            val headerCells = sh.getRow(0)
            fun columnIndex(vararg names: String): Int {
                if (headerCells == null) return -1
                for (cell in headerCells) {
                    val n = normalize(textOf(cell))
                    if (names.any { normalize(it) == n }) return cell.columnIndex
                }
                return -1
            }
            // Incremental export is tracked by the local Room database. Avoid scanning the entire
            // Follow up sheet for duplicate detection because that becomes expensive for large workbooks.
            var rowNumber = maxOf(1, sh.lastRowNum + 1)
            val exportedIds = ArrayList<Long>(followups.size)
            for (followup in followups) {
                val account = accounts[followup.accountId] ?: continue
                val row = sh.createRow(rowNumber++)
                row.createCell(srColumn).setCellValue(srNo)
                val values = listOf(
                    createdFormat.format(Date(followup.createdAt)), account.branch, account.accountNo, account.name,
                    followup.date, followup.mode, followup.personMet, followup.discussion,
                    followup.commitment.toString(), followup.nextDate, followup.remarks
                )
                var valueIndex = 0
                for (column in 0 until maxOf(standardHeaders.size, srColumn + 1)) {
                    if (column == srColumn) continue
                    if (valueIndex >= values.size) break
                    row.createCell(column).setCellValue(values[valueIndex++])
                }
                exportedIds += followup.id
            }

            for (i in 0 until maxOf(standardHeaders.size, srColumn + 1)) {
                if (sh.getColumnWidth(i) == 2048) sh.setColumnWidth(i, 18 * 256)
            }

            val temp = File(context.cacheDir, "recovery_export.xlsx")
            FileOutputStream(temp).use { wb.write(it) }
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> temp.inputStream().use { it.copyTo(out) } }

            if (exportedIds.isNotEmpty()) db.followUpDao().markExported(exportedIds)
            return exportedIds.size
        } finally {
            wb.close()
        }
    }
}
