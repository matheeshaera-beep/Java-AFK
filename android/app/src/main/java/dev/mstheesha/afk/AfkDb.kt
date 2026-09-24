package dev.mstheesha.afk

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val port: Int = 25565,
    val chatCommand: String = "",
    val commandDelaySeconds: Int = 5,
    val onlineMode: Boolean = false,
    val username: String = "",
    val viewDistance: Int = 2,
    val chatMode: String = "enabled", // enabled | commandsOnly | hidden
    val loopEnabled: Boolean = false,
    val loopMessage: String = "",
    val loopDelaySeconds: Int = 30,
)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY id")
    fun all(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    fun byId(id: Long): Flow<ServerEntity?>

    @Insert
    suspend fun insert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)
}

@Database(entities = [ServerEntity::class], version = 6, exportSchema = false)
abstract class AfkDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var instance: AfkDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN onlineMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN username TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN viewDistance INTEGER NOT NULL DEFAULT 12")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN chatMode TEXT NOT NULL DEFAULT 'enabled'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN loopEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE servers ADD COLUMN loopMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN loopDelaySeconds INTEGER NOT NULL DEFAULT 30")
            }
        }

        fun get(context: Context): AfkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AfkDatabase::class.java,
                    "afk.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}