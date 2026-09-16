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

@Database(entities = [ServerEntity::class], version = 2, exportSchema = false)
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

        fun get(context: Context): AfkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AfkDatabase::class.java,
                    "afk.db",
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}