package com.example.recoveryfield.data

import androidx.room.*
import androidx.room.migration.Migration

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["accountNo"]),
        Index(value = ["branch"]),
        Index(value = ["name"]),
        Index(value = ["balance"]),
        Index(value = ["npaDate"])
    ]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val branch: String = "",
    val accountNo: String = "",
    val cif: String = "",
    val name: String = "",
    val product: String = "",
    val npaDate: String = "",
    val approvalDate: String = "",
    val balance: Double = 0.0,
    val ngo: String = "",
    val category: String = "",
    val extraJson: String = ""
)



@Entity(
    tableName = "members",
    indices = [Index(value = ["loanAccountNo"])]
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanAccountNo: String = "",
    val memberName: String = "",
    val sbAccountNo: String = "",
    val sbBalance: Double = 0.0
)

@Entity(
    tableName = "followups",
    indices = [Index("accountId"), Index("nextDate")]
)
data class FollowUpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val date: String,
    val mode: String,
    val personMet: String,
    val discussion: String,
    val commitment: Double,
    val nextDate: String,
    val remarks: String,
    val exported: Boolean = false
)

data class BalanceBucket(
    val label: String,
    val min: Double,
    val max: Double?,
    val accountCount: Int,
    val totalBalance: Double
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY name LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<AccountEntity>

    @Query("""SELECT * FROM accounts
        WHERE (:branch = '' OR branch = :branch)
          AND (:q = '' OR name LIKE '%' || :q || '%' OR accountNo LIKE '%' || :q || '%')
          AND balance >= :minBalance
          AND (:maxBalance IS NULL OR balance <= :maxBalance)
        ORDER BY
          CASE WHEN :sort = 'balance' AND :descending = 0 THEN balance END ASC,
          CASE WHEN :sort = 'balance' AND :descending = 1 THEN balance END DESC,
          CASE WHEN :sort = 'npa' AND :descending = 0 THEN npaDate END ASC,
          CASE WHEN :sort = 'npa' AND :descending = 1 THEN npaDate END DESC,
          CASE WHEN :sort = 'account' AND :descending = 0 THEN accountNo END ASC,
          CASE WHEN :sort = 'account' AND :descending = 1 THEN accountNo END DESC,
          CASE WHEN :descending = 0 THEN name END ASC,
          CASE WHEN :descending = 1 THEN name END DESC
        LIMIT :limit OFFSET :offset""")
    suspend fun paged(
        q: String, branch: String, minBalance: Double, maxBalance: Double?,
        sort: String, descending: Int, limit: Int, offset: Int
    ): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(balance), 0) FROM accounts")
    suspend fun totalBalance(): Double

    @Query("SELECT DISTINCT branch FROM accounts WHERE branch <> '' ORDER BY branch")
    suspend fun branches(): List<String>

    @Query("SELECT COUNT(*) FROM accounts WHERE balance >= :min AND (:max IS NULL OR balance <= :max)")
    suspend fun countRange(min: Double, max: Double?): Int

    @Query("SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE balance >= :min AND (:max IS NULL OR balance <= :max)")
    suspend fun sumRange(min: Double, max: Double?): Double

    suspend fun balanceBuckets(): List<BalanceBucket> {
        val definitions = listOf(
            "₹0 – ₹10,000" to (0.0 to 10000.0),
            "₹10,001 – ₹30,000" to (10001.0 to 30000.0),
            "₹30,001 – ₹50,000" to (30001.0 to 50000.0),
            "₹50,001 – ₹1 lakh" to (50001.0 to 100000.0),
            "₹1 lakh – ₹3 lakhs" to (100001.0 to 300000.0),
            "₹3 lakhs – ₹5 lakhs" to (300001.0 to 500000.0),
            "₹5 lakhs – ₹10 lakhs" to (500001.0 to 1000000.0),
            "Above ₹10 lakhs" to (1000001.0 to null)
        )
        return definitions.map { (label, range) ->
            BalanceBucket(label, range.first, range.second, countRange(range.first, range.second), sumRange(range.first, range.second))
        }
    }
}



@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE loanAccountNo = :loanAccountNo ORDER BY memberName, id")
    suspend fun forLoanAccount(loanAccountNo: String): List<MemberEntity>

    @Insert
    suspend fun insertAll(items: List<MemberEntity>)

    @Query("DELETE FROM members")
    suspend fun clear()
}

@Dao
interface FollowUpDao {
    @Query("SELECT * FROM followups WHERE accountId = :accountId ORDER BY date DESC, createdAt DESC")
    suspend fun forAccount(accountId: Long): List<FollowUpEntity>

    @Query("SELECT * FROM followups WHERE nextDate = :date ORDER BY nextDate, createdAt")
    suspend fun due(date: String): List<FollowUpEntity>

    @Query("SELECT COUNT(*) FROM followups WHERE nextDate = :date")
    suspend fun dueCount(date: String): Int

    @Query("SELECT COUNT(*) FROM followups WHERE nextDate < :date")
    suspend fun overdueCount(date: String): Int

    @Query("SELECT COUNT(*) FROM followups WHERE nextDate > :date")
    suspend fun upcomingCount(date: String): Int

    @Query("SELECT * FROM followups ORDER BY createdAt")
    suspend fun all(): List<FollowUpEntity>

    @Insert
    suspend fun insert(item: FollowUpEntity): Long

    @Query("SELECT * FROM followups WHERE exported = 0 ORDER BY createdAt")
    suspend fun unexported(): List<FollowUpEntity>

    @Query("UPDATE followups SET exported = 1 WHERE id IN (:ids)")
    suspend fun markExported(ids: List<Long>)
}

@Database(entities = [AccountEntity::class, FollowUpEntity::class, MemberEntity::class], version = 6, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun followUpDao(): FollowUpDao
    abstract fun memberDao(): MemberDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_accounts_accountNo_branch")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_accountNo ON accounts(accountNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_branch ON accounts(branch)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_name ON accounts(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_balance ON accounts(balance)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_accounts_npaDate ON accounts(npaDate)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE followups ADD COLUMN exported INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE followups SET date = substr(date, 9, 2) || '-' || substr(date, 6, 2) || '-' || substr(date, 1, 4) WHERE date GLOB '????-??-??'")
                db.execSQL("UPDATE followups SET nextDate = substr(nextDate, 9, 2) || '-' || substr(nextDate, 6, 2) || '-' || substr(nextDate, 1, 4) WHERE nextDate GLOB '????-??-??'")
                db.execSQL("UPDATE accounts SET npaDate = substr(npaDate, 9, 2) || '-' || substr(npaDate, 6, 2) || '-' || substr(npaDate, 1, 4) WHERE npaDate GLOB '????-??-??'")
                db.execSQL("UPDATE accounts SET approvalDate = substr(approvalDate, 9, 2) || '-' || substr(approvalDate, 6, 2) || '-' || substr(approvalDate, 1, 4) WHERE approvalDate GLOB '????-??-??'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN approvalDate TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS members (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        loanAccountNo TEXT NOT NULL,
                        memberName TEXT NOT NULL,
                        sbAccountNo TEXT NOT NULL,
                        sbBalance REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_members_loanAccountNo ON members(loanAccountNo)")
            }
        }

        fun get(context: android.content.Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "recovery.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
        }
    }
}
