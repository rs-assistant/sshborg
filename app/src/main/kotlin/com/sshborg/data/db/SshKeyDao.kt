package com.sshborg.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY label ASC")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys ORDER BY label ASC")
    suspend fun getAllOnce(): List<SshKeyEntity>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: SshKeyEntity): Long

    @Query("UPDATE ssh_keys SET encryptedBlob = :replacement WHERE id = :id AND encryptedBlob = :expected")
    suspend fun replaceEncryptedBlob(id: Long, expected: String, replacement: String): Int

    @Query("UPDATE ssh_keys SET label = :label, publicKey = :publicKey WHERE id = :id")
    suspend fun rename(id: Long, label: String, publicKey: String)

    @Delete
    suspend fun delete(key: SshKeyEntity)
}
