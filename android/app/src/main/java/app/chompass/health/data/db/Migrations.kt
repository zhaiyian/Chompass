package app.chompass.health.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * ## 规则（写在这里，是因为以后一定会有人问"改表要不要写迁移"）
 *
 * 1. **任何 schema 变更都必须：改实体 → 升 `version` → 在 [ALL] 里加一条 Migration。**
 *    三者缺一，老用户升级时 Room 会抛
 *    `IllegalStateException: Room cannot verify the data integrity`，也就是启动即崩。
 * 2. **不允许 `fallbackToDestructiveMigration()`。** 这个 App 的数据是用户几个月的
 *    尿酸与疼痛记录，清库重建等于把用户资产删了。
 * 3. **迁移里禁止读业务代码**（仓库、ViewModel、枚举）。迁移要能在任意未来版本
 *    稳定重放，引用会变的东西等于给未来埋雷。只用 SQL 字面量。
 * 4. **每条迁移都要拷数据，不只是建表。** 新增列能算出合理初值就必须回填，
 *    否则老用户会看到一堆"空字段"。
 * 5. SQLite 不支持 `ALTER COLUMN`，改可空性/改外键只能重建表：
 *    建新表 → `INSERT ... SELECT` 拷数据 → `DROP` 旧表 → `RENAME`。
 *    **索引名必须和 Room 生成的一致**（`index_<表>_<列>`），否则校验不过。
 * 6. 迁移写完后：把 `app/schemas/` 下新版本的 JSON 一起提交，评审时用它做 diff。
 */
object Migrations {

    /**
     * v1 → v2：`dose_log` 补 `unit_label`，并把 `medication_id` 改为可空。
     *
     * 两个改动一起做，因为它们都必须重建表，分成两条迁移等于把整表拷两遍。
     *
     * **unit_label**：v1 只存 `intake_units`（数字）没存单位，C2 只能硬编码"片"，
     * 于是"1 粒胶囊"会显示成"1 片"。回填时按 `medication_id` 取药当前的单位。
     *
     * **medication_id 可空**：从备份导入的历史记录很可能对应不到本机任何一味药
     * （换机后药单是空的）。`NOT NULL + 外键` 会让导入一条都进不来。
     * 可空是这个字段本来该有的形态——"这条记录属于哪味药"本来就允许未知。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 必须先删掉 v1 手工建的部分唯一索引，否则迁移后的 schema 校验会失败：
            // Room 只认 `app/schemas` 里记录的结构，而这条索引是 `onCreate` 里 execSQL
            // 建的（@Index 不支持 WHERE），1.json 里没有它。
            // 校验是"集合相等"比较，数据库多一个索引就报
            // `Migration didn't properly handle: pain_episode`，也就是**升级即崩**。
            // v1 新装时不崩，是因为 onCreate 路径不做 schema 校验——这颗雷只在升级时炸。
            db.execSQL("DROP INDEX IF EXISTS `idx_episode_ongoing`")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `dose_log_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `instance_id` INTEGER,
                    `medication_id` INTEGER,
                    `actual_at` INTEGER NOT NULL,
                    `actual_date` TEXT NOT NULL,
                    `plan_at` INTEGER,
                    `deviation_minutes` INTEGER,
                    `drug_name` TEXT NOT NULL,
                    `strength_value` REAL,
                    `strength_unit` TEXT,
                    `intake_units` REAL NOT NULL,
                    `unit_label` TEXT NOT NULL DEFAULT '片',
                    `kind` TEXT NOT NULL,
                    `note` TEXT,
                    `revoked_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`instance_id`) REFERENCES `dose_instance`(`id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`medication_id`) REFERENCES `medication`(`id`)
                        ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent()
            )

            // 单位回填：能对上药的取药当前单位；对不上的保留默认「片」
            db.execSQL(
                """
                INSERT INTO `dose_log_new` (
                    `id`, `instance_id`, `medication_id`, `actual_at`, `actual_date`,
                    `plan_at`, `deviation_minutes`, `drug_name`, `strength_value`,
                    `strength_unit`, `intake_units`, `unit_label`, `kind`, `note`,
                    `revoked_at`, `created_at`
                )
                SELECT
                    l.`id`, l.`instance_id`, l.`medication_id`, l.`actual_at`, l.`actual_date`,
                    l.`plan_at`, l.`deviation_minutes`, l.`drug_name`, l.`strength_value`,
                    l.`strength_unit`, l.`intake_units`,
                    COALESCE(
                        (SELECT m.`unit_label` FROM `medication` m WHERE m.`id` = l.`medication_id`),
                        '片'
                    ),
                    l.`kind`, l.`note`, l.`revoked_at`, l.`created_at`
                FROM `dose_log` l
                """.trimIndent()
            )

            db.execSQL("DROP TABLE `dose_log`")
            db.execSQL("ALTER TABLE `dose_log_new` RENAME TO `dose_log`")

            // 索引名必须与 Room 的期望逐字一致，否则打开时校验失败
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_log_actual_at` " +
                    "ON `dose_log` (`actual_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_log_medication_id_actual_at` " +
                    "ON `dose_log` (`medication_id`, `actual_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dose_log_instance_id` " +
                    "ON `dose_log` (`instance_id`)"
            )
        }
    }

    /**
     * v2 → v3：新增 `pain_update`（发作过程中的疼痛变化打点）。
     *
     * 纯建表，不动既有数据 —— 老发作没有打点，当前评分自动退化
     * 为 `pain_episode.nrs`（开始评分），统计口径不变。
     *
     * 趋势（加重 / 减轻）**不建列**：它由相邻两点的 nrs 差值推导，
     * 落库会与评分互相矛盾。理由见 [app.chompass.health.data.db.entity.PainUpdateEntity]。
     *
     * 外键 ON DELETE CASCADE 不会真的触发（发作走软删除），
     * 它的作用只是防止孤儿行。Room 要求外键列必须有索引，共两个索引。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pain_update` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `episode_id` INTEGER NOT NULL,
                    `at` INTEGER NOT NULL,
                    `nrs` INTEGER NOT NULL,
                    `note` TEXT,
                    `deleted_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`episode_id`) REFERENCES `pain_episode`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pain_update_episode_id` " +
                    "ON `pain_update` (`episode_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pain_update_at` " +
                    "ON `pain_update` (`at`)"
            )
        }
    }

    /** 传给 `addMigrations()` 的全集，新迁移往后追加即可 */
    /**
     * v3 → v4：新增 `pain_joint` 解剖树，并把老的 `pain_episode.site_code` 映射到新 id。
     *
     * 部位从"扁平字典 9 项"改成"大区 → 趾 / 指 → 关节"三级。表本身是内置只读枚举
     * （39 行），随迁移灌进去；用户自己补的"说不清的部位"仍留在 `dict_item`，
     * 所以 `pain_joint` 不需要 builtin 列。
     *
     * **映射在迁移里做**，不留到运行时：老 code（`FOOT_MTP1` / `FOOT_DORSAL` /
     * `HAND_THUMB`）在 `dict_item` 里仍然存在，如果只是"读的时候兼容"，
     * 同一条记录会在"新界面的关节名"和"老字典名"之间来回变，用户会以为数据被改了。
     * 直接改成新 id，第二个真相源就不存在了。
     *
     * `HAND_THUMB` → `THUMB_MCP` 是有损的（老字典只有"手拇指"，没区分掌指/指间），
     * 取更常见的掌指关节。用户可以在编辑页改成指间关节。
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pain_joint` (
                    `joint_id` TEXT NOT NULL,
                    `region_id` TEXT NOT NULL,
                    `region_name` TEXT NOT NULL,
                    `region_order` INTEGER NOT NULL,
                    `digit_id` TEXT,
                    `digit_name` TEXT,
                    `digit_order` INTEGER,
                    `display_name` TEXT NOT NULL,
                    `anatomy_name` TEXT NOT NULL,
                    `disease_tag` TEXT NOT NULL,
                    `side_required` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    PRIMARY KEY(`joint_id`)
                )
                """.trimIndent()
            )
            // 39 行解剖枚举直接写死在这里，不引用 `JointSeed`：
            // 迁移要能在任意未来版本稳定重放。引用会随版本变化的种子表，
            // 会让"从 v3 升上来"和"新装 v4"两份库的关节名悄悄不一致。
            db.execSQL(
                """
                INSERT OR IGNORE INTO `pain_joint`
                    (`joint_id`,`region_id`,`region_name`,`region_order`,
                     `digit_id`,`digit_name`,`digit_order`,
                     `display_name`,`anatomy_name`,`disease_tag`,`side_required`,`sort_order`)
                VALUES
                ('MTP1','TOE','脚趾 / 前脚掌',1,'TOE1','大脚趾',1,'根部','第一跖趾关节','GOUT_HIGH',1,10),
                ('TOE1_IP','TOE','脚趾 / 前脚掌',1,'TOE1','大脚趾',1,'指尖侧','拇趾趾间关节','GOUT_HIGH',1,20),
                ('TOE2_MTP','TOE','脚趾 / 前脚掌',1,'TOE2','第二趾',2,'根部','第二跖趾关节','BOTH',1,10),
                ('TOE2_PIP','TOE','脚趾 / 前脚掌',1,'TOE2','第二趾',2,'中段','第二趾近端趾间关节','BOTH',1,20),
                ('TOE2_DIP','TOE','脚趾 / 前脚掌',1,'TOE2','第二趾',2,'指尖侧','第二趾远端趾间关节','BOTH',1,30),
                ('TOE3_MTP','TOE','脚趾 / 前脚掌',1,'TOE3','第三趾',3,'根部','第三跖趾关节','BOTH',1,10),
                ('TOE3_PIP','TOE','脚趾 / 前脚掌',1,'TOE3','第三趾',3,'中段','第三趾近端趾间关节','BOTH',1,20),
                ('TOE3_DIP','TOE','脚趾 / 前脚掌',1,'TOE3','第三趾',3,'指尖侧','第三趾远端趾间关节','BOTH',1,30),
                ('TOE4_MTP','TOE','脚趾 / 前脚掌',1,'TOE4','第四趾',4,'根部','第四跖趾关节','BOTH',1,10),
                ('TOE4_PIP','TOE','脚趾 / 前脚掌',1,'TOE4','第四趾',4,'中段','第四趾近端趾间关节','BOTH',1,20),
                ('TOE4_DIP','TOE','脚趾 / 前脚掌',1,'TOE4','第四趾',4,'指尖侧','第四趾远端趾间关节','BOTH',1,30),
                ('TOE5_MTP','TOE','脚趾 / 前脚掌',1,'TOE5','小趾',5,'根部','第五跖趾关节','BOTH',1,10),
                ('TOE5_PIP','TOE','脚趾 / 前脚掌',1,'TOE5','小趾',5,'中段','第五趾近端趾间关节','BOTH',1,20),
                ('TOE5_DIP','TOE','脚趾 / 前脚掌',1,'TOE5','小趾',5,'指尖侧','第五趾远端趾间关节','BOTH',1,30),
                ('FOREFOOT','TOE','脚趾 / 前脚掌',1,NULL,NULL,NULL,'前脚掌 / 足背','跖跗关节区','GOUT_HIGH',1,90),
                ('THUMB_MCP','FINGER','手指',2,'THUMB','大拇指',1,'根部','拇指掌指关节','RA_HIGH',1,10),
                ('THUMB_IP','FINGER','手指',2,'THUMB','大拇指',1,'指尖侧','拇指指间关节','BOTH',1,20),
                ('INDEX_MCP','FINGER','手指',2,'INDEX','食指',2,'根部','食指掌指关节','RA_HIGH',1,10),
                ('INDEX_PIP','FINGER','手指',2,'INDEX','食指',2,'中段','食指近端指间关节','RA_HIGH',1,20),
                ('INDEX_DIP','FINGER','手指',2,'INDEX','食指',2,'指尖侧','食指远端指间关节','RA_HIGH',1,30),
                ('MIDDLE_MCP','FINGER','手指',2,'MIDDLE','中指',3,'根部','中指掌指关节','RA_HIGH',1,10),
                ('MIDDLE_PIP','FINGER','手指',2,'MIDDLE','中指',3,'中段','中指近端指间关节','RA_HIGH',1,20),
                ('MIDDLE_DIP','FINGER','手指',2,'MIDDLE','中指',3,'指尖侧','中指远端指间关节','RA_HIGH',1,30),
                ('RING_MCP','FINGER','手指',2,'RING','无名指',4,'根部','无名指掌指关节','RA_HIGH',1,10),
                ('RING_PIP','FINGER','手指',2,'RING','无名指',4,'中段','无名指近端指间关节','RA_HIGH',1,20),
                ('RING_DIP','FINGER','手指',2,'RING','无名指',4,'指尖侧','无名指远端指间关节','RA_HIGH',1,30),
                ('LITTLE_MCP','FINGER','手指',2,'LITTLE','小指',5,'根部','小指掌指关节','RA_HIGH',1,10),
                ('LITTLE_PIP','FINGER','手指',2,'LITTLE','小指',5,'中段','小指近端指间关节','RA_HIGH',1,20),
                ('LITTLE_DIP','FINGER','手指',2,'LITTLE','小指',5,'指尖侧','小指远端指间关节','RA_HIGH',1,30),
                ('WRIST','WRIST','手腕',3,NULL,NULL,NULL,'手腕','腕关节','RA_HIGH',1,10),
                ('WRIST_CMC','WRIST','手腕',3,NULL,NULL,NULL,'拇指侧根部','拇指腕掌关节','BOTH',1,20),
                ('ANKLE','ANKLE','脚踝 / 足跟',4,NULL,NULL,NULL,'脚踝','踝关节','BOTH',1,10),
                ('HEEL','ANKLE','脚踝 / 足跟',4,NULL,NULL,NULL,'足跟','跟骨区','GOUT_HIGH',1,20),
                ('KNEE','KNEE','膝盖',5,NULL,NULL,NULL,'膝盖','膝关节','BOTH',1,10),
                ('ELBOW','OTHER','其他大关节',6,NULL,NULL,NULL,'肘关节','肘关节','BOTH',1,10),
                ('SHOULDER','OTHER','其他大关节',6,NULL,NULL,NULL,'肩关节','肩关节','GENERAL',1,20),
                ('HIP','OTHER','其他大关节',6,NULL,NULL,NULL,'髋关节 / 胯部','髋关节','GENERAL',1,30),
                ('SPINE','OTHER','其他大关节',6,NULL,NULL,NULL,'腰背 / 脊柱','脊柱','GENERAL',0,40),
                ('OTHER','OTHER','其他大关节',6,NULL,NULL,NULL,'其他 / 说不清','—','GENERAL',0,90)
                """.trimIndent()
            )

            // 部位文案只留一条解析路径：pain_joint 投影一份到 dict_item(SITE)。
            // siteLabel / CSV 导出 / CSV 导入 / B6 统计都走 dict.label(SITE, code)，
            // 这样这四处一行都不用改，也不会出现"两个地方各有一份文案"的漂移。
            db.execSQL(
                """
                INSERT OR IGNORE INTO `dict_item`
                    (`type`,`code`,`label`,`short_label`,`sort_order`,`enabled`,`builtin`,`created_at`,`updated_at`)
                SELECT 'SITE', `joint_id`,
                       COALESCE(`digit_name`, '') || `display_name`,
                       NULL,
                       `region_order` * 1000 + COALESCE(`digit_order`, 0) * 100 + `sort_order`,
                       1, 1, ?, ?
                FROM `pain_joint`
                """.trimIndent(),
                arrayOf<Any?>(now, now)
            )
            // 老的 9 个内置部位停用（不删：历史记录的 code 仍要能解析出文案）。
            db.execSQL(
                """
                UPDATE `dict_item` SET `enabled` = 0
                WHERE `type` = 'SITE' AND `builtin` = 1
                  AND `code` IN ('FOOT_MTP1','FOOT_DORSAL','HAND_THUMB','ANKLE','KNEE','HEEL','WRIST','ELBOW','OTHER')
                """.trimIndent()
            )
            // 上一步会误伤 6 个**与老 code 同名的关节**（ANKLE / KNEE / HEEL / WRIST / ELBOW / OTHER）：
            // 上面的 INSERT OR IGNORE 因为 (type,code) 唯一索引跳过了它们，于是这些关节
            // 在字典里还是"停用的老项"。这一步把它们收编成关节表的文案并重新启用。
            // 实测过：漏掉这一步，关节表 39 行只有 33 行是 enabled 的。
            db.execSQL(
                """
                UPDATE `dict_item` SET
                    `enabled` = 1,
                    `label` = (SELECT COALESCE(`digit_name`, '') || `display_name`
                               FROM `pain_joint` p WHERE p.`joint_id` = `dict_item`.`code`),
                    `sort_order` = (SELECT `region_order` * 1000 + COALESCE(`digit_order`, 0) * 100 + `sort_order`
                                    FROM `pain_joint` p WHERE p.`joint_id` = `dict_item`.`code`),
                    `updated_at` = ?
                WHERE `type` = 'SITE' AND `builtin` = 1
                  AND `code` IN (SELECT `joint_id` FROM `pain_joint`)
                """.trimIndent(),
                arrayOf<Any?>(now)
            )

            // 老 site_code → 新 joint_id。写成字面量而不是引用 JointSeed：
            // 迁移要能在任意未来版本稳定重放，引用会变的东西等于给未来埋雷。
            db.execSQL("UPDATE `pain_episode` SET `site_code` = 'MTP1' WHERE `site_code` = 'FOOT_MTP1'")
            db.execSQL("UPDATE `pain_episode` SET `site_code` = 'FOREFOOT' WHERE `site_code` = 'FOOT_DORSAL'")
            db.execSQL("UPDATE `pain_episode` SET `site_code` = 'THUMB_MCP' WHERE `site_code` = 'HAND_THUMB'")
            // 手拇指没有侧别以外的信息可留，其余 6 个 code 与新 id 同名，不用动。
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
