package com.cryptosim.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM account WHERE id = 1")
    fun observeAccount(): Flow<AccountEntity?>

    @Query("SELECT * FROM positions ORDER BY openedAt DESC")
    fun observePositions(): Flow<List<PositionEntity>>

    @Query("SELECT * FROM trades ORDER BY createdAt DESC")
    fun observeTrades(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM account WHERE id = 1")
    suspend fun getAccount(): AccountEntity?

    @Query("SELECT * FROM positions ORDER BY openedAt DESC")
    suspend fun getPositions(): List<PositionEntity>

    @Query("SELECT * FROM trades ORDER BY createdAt DESC")
    suspend fun getTrades(): List<TradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(entity: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPositions(entities: List<PositionEntity>)

    @Insert
    suspend fun insertTrade(entity: TradeEntity)

    @Insert
    suspend fun insertTrades(entities: List<TradeEntity>)

    @Query("DELETE FROM positions")
    suspend fun clearPositions()

    @Query("DELETE FROM trades")
    suspend fun clearTrades()

    @Query("DELETE FROM trades WHERE id NOT IN (SELECT id FROM trades ORDER BY createdAt DESC LIMIT :limit)")
    suspend fun trimTrades(limit: Int)

    @Query("DELETE FROM positions WHERE id = :id")
    suspend fun deletePosition(id: String)

    @Transaction
    suspend fun replacePositions(entities: List<PositionEntity>) {
        clearPositions()
        upsertPositions(entities)
    }

    @Transaction
    suspend fun replaceAll(
        account: AccountEntity,
        positions: List<PositionEntity>,
        trades: List<TradeEntity>,
    ) {
        upsertAccount(account)
        clearPositions()
        upsertPositions(positions)
        clearTrades()
        insertTrades(trades)
    }
}
