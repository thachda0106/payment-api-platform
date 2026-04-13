# Phase 07a — The Accounting Equation Explained

## MoMo-like Payment API Platform

> **Document Status**: Final v1.0
> **Last Updated**: 2026-04-13
> **Classification**: INTERNAL — Engineering Education
> **Audience**: Backend Engineers, Fullstack Engineers
> **Prerequisite**: None — Written from first principles
> **Purpose**: Giải thích chi tiết bản chất phương trình kế toán mở rộng, phục vụ việc hiểu và xây dựng hệ thống Ledger trong Phase 07

---

## 1. Mục tiêu của tài liệu này

Tài liệu này giải thích **bản chất triết học và toán học** của phương trình kế toán mở rộng:

```
ASSET + EXPENSE = LIABILITY + EQUITY + REVENUE
```

Mọi hệ thống Ledger (sổ cái) trong ngành tài chính — từ Stripe, Square, MoMo cho tới Core Banking — đều được xây dựng trên nền tảng phương trình này. Nếu không hiểu nó, bạn không thể thiết kế đúng schema, viết đúng stored procedure, hay debug được lỗi mất cân bằng trong sổ cái.

---

## 2. Xuất phát điểm: Phương trình cơ bản 3 thành phần

### 2.1 Entity Concept — Công ty là một "Cái Hộp Rỗng"

Trong kế toán, Công ty (hoặc Hệ thống Ví điện tử) được xem như một thực thể trừu tượng, một **cái hộp rỗng**. Bản thân cái hộp không sở hữu gì cả. Mọi thứ bên trong hộp đều do **Ai Đó** cung cấp.

Từ đó sinh ra 2 câu hỏi cốt lõi:

| Câu hỏi | Câu trả lời | Thuật ngữ kế toán |
| :--- | :--- | :--- |
| Cái hộp đang **chứa những gì**? | Tiền mặt, Server, Đất đai... | **ASSET (Tài sản)** |
| Những thứ đó **do ai cung cấp**? | Do chủ bỏ vào hoặc đi vay bên ngoài | **EQUITY (Vốn chủ) + LIABILITY (Nợ phải trả)** |

### 2.2 Phương trình cơ bản

Vì mọi thứ trong hộp luôn được cung cấp bởi ai đó, ta có đẳng thức tuyệt đối:

```
ASSET = LIABILITY + EQUITY
```

**Đây là một Tiên Đề (Axiom). Nó luôn đúng, ở mọi thời điểm, sau mọi giao dịch.**

| Thành phần | Ý nghĩa | Ví dụ trong Payment System |
| :--- | :--- | :--- |
| **ASSET** | Nguồn lực mà hệ thống đang nắm giữ | Tiền trong tài khoản VNPay/Vietcombank của công ty |
| **LIABILITY** | Nghĩa vụ phải trả cho người khác | Số dư ví của khách hàng (công ty giữ hộ) |
| **EQUITY** | Phần thuộc về chủ sở hữu | Vốn góp ban đầu, lợi nhuận giữ lại |

### 2.3 Ví dụ minh họa

**Ngày 1: Ông Chủ bỏ 1 tỷ vào tài khoản ngân hàng công ty.**

```
ASSET (1 tỷ tiền bank) = LIABILITY (0) + EQUITY (1 tỷ vốn chủ)
           1,000,000,000 = 0 + 1,000,000,000  ✅ Cân bằng
```

**Ngày 2: Khách hàng A nạp 100k vào ví điện tử qua VNPay.**

Tiền thật chảy vào tài khoản VNPay của công ty (+100k Asset), đồng thời công ty nợ khách A 100k (+100k Liability).

```
ASSET (1 tỷ + 100k) = LIABILITY (100k) + EQUITY (1 tỷ)
       1,000,100,000 = 100,000 + 1,000,000,000  ✅ Cân bằng
```

---

## 3. Mở rộng: REVENUE và EXPENSE đến từ đâu?

### 3.1 Bản chất của Revenue và Expense

Trong quá trình hoạt động, công ty sẽ:
- **Kiếm tiền** (bán hàng, thu phí giao dịch...) → Gọi là **REVENUE (Doanh thu)**
- **Tiêu tiền** (trả lương, phí server, phí gateway...) → Gọi là **EXPENSE (Chi phí)**

Cả hai thứ này đều **ảnh hưởng trực tiếp đến EQUITY**:
- Revenue làm **TĂNG** Equity (công ty giàu hơn)
- Expense làm **GIẢM** Equity (công ty nghèo hơn)

### 3.2 Phân rã EQUITY

Do Revenue và Expense là 2 dòng chảy liên tục ảnh hưởng đến Equity, ta tách Equity ra thành:

```
EQUITY = Vốn Gốc Ban Đầu + REVENUE − EXPENSE
```

### 3.3 Thay vào phương trình gốc

```
ASSET = LIABILITY + (Vốn Gốc + REVENUE − EXPENSE)
```

Chuyển EXPENSE sang vế trái (cộng cả hai vế cho EXPENSE):

```
ASSET + EXPENSE = LIABILITY + EQUITY + REVENUE
```

> **Lưu ý**: Trong phương trình này, `EQUITY` được hiểu là **Vốn gốc tích lũy** (Original Capital + Retained Earnings từ các kỳ trước), không bao gồm Revenue/Expense của kỳ hiện tại.

---

## 4. Ý nghĩa cốt lõi của phương trình mở rộng

### 4.1 Phương trình đầy đủ

```
┌─────────────────────────┐     ┌────────────────────────────────────┐
│       VẾ TRÁI           │  =  │           VẾ PHẢI                  │
│                         │     │                                    │
│   ASSET + EXPENSE       │     │   LIABILITY + EQUITY + REVENUE     │
│                         │     │                                    │
│  (Đang giữ gì +        │     │  (Nợ ai + Vốn chủ +               │
│   Đã tiêu gì)          │     │   Đã kiếm được gì)                │
└─────────────────────────┘     └────────────────────────────────────┘
```

**Cách đọc:**
- **Vế trái**: Công ty đang **giữ** những gì (Asset) và đã **tiêu** gì (Expense)?
- **Vế phải**: Tất cả nguồn đó đến từ đâu? Nợ người ta (Liability), vốn chủ bỏ vào (Equity), hay do kinh doanh sinh ra (Revenue)?

### 4.2 Tại sao EXPENSE nằm cùng vế với ASSET?

Đây là điểm gây nhầm lẫn nhất. Hãy nghĩ thế này:

- **ASSET**: Tiền vẫn còn đang nằm trong hộp (ví dụ: 500k tiền mặt).
- **EXPENSE**: Tiền đã rời khỏi hộp để đổi lấy dịch vụ/hàng hóa (ví dụ: 50k tiền AWS).

Cả hai đều là **hình thái sử dụng giá trị**. Asset là giá trị bạn đang giữ. Expense là giá trị bạn đã tiêu xài. Chúng cùng trả lời câu hỏi: **"Giá trị đã đi đâu?"**

### 4.3 Tại sao REVENUE nằm cùng vế với LIABILITY?

- **LIABILITY**: Nguồn giá trị đến từ người ngoài cho mượn/gửi (khách nạp ví, vay ngân hàng).
- **REVENUE**: Nguồn giá trị do hoạt động kinh doanh tạo ra (bán hàng, thu phí).

Cả hai đều là **nguồn cung cấp giá trị**. Chúng cùng trả lời câu hỏi: **"Giá trị đến từ đâu?"**

---

## 5. Debit và Credit — Quy ước từ phương trình

### 5.1 Nguồn gốc của Debit/Credit

DEBIT và CREDIT **không phải** là Cộng và Trừ. Chúng là **quy ước lịch sử** (từ năm 1494, bởi Luca Pacioli) được thiết kế để giữ cho phương trình luôn cân bằng.

Quy ước này cực kỳ đơn giản:

```
Thành phần nằm ở VẾ TRÁI  →  DEBIT làm TĂNG,  CREDIT làm GIẢM
Thành phần nằm ở VẾ PHẢI  →  CREDIT làm TĂNG, DEBIT làm GIẢM
```

### 5.2 Bảng quy ước đầy đủ

| Account Type | Vế của phương trình | DEBIT | CREDIT | Normal Balance |
| :--- | :--- | :--- | :--- | :--- |
| **ASSET** | Trái | ⬆️ Tăng | ⬇️ Giảm | DEBIT |
| **EXPENSE** | Trái | ⬆️ Tăng | ⬇️ Giảm | DEBIT |
| **LIABILITY** | Phải | ⬇️ Giảm | ⬆️ Tăng | CREDIT |
| **EQUITY** | Phải | ⬇️ Giảm | ⬆️ Tăng | CREDIT |
| **REVENUE** | Phải | ⬇️ Giảm | ⬆️ Tăng | CREDIT |

### 5.3 Normal Balance là gì?

**Normal Balance** là chiều (Debit hoặc Credit) làm **TĂNG** giá trị tài khoản đó. Nó cũng quyết định công thức tính Số Dư (Balance):

```
Nếu Normal Balance = DEBIT  →  Balance = Tổng Debit − Tổng Credit
Nếu Normal Balance = CREDIT →  Balance = Tổng Credit − Tổng Debit
```

Trong code (tham chiếu stored procedure ở `07-data-architecture.md`):

```sql
-- Trích từ create_journal_entry procedure
IF v_acc.normal_balance = 'CREDIT' THEN
    -- Credit tăng, Debit giảm
    v_new_bal := v_prev_bal + CASE WHEN v_line.entry_type='CREDIT' THEN v_line.amount ELSE -v_line.amount END;
ELSIF v_acc.normal_balance = 'DEBIT' THEN
    -- Debit tăng, Credit giảm
    v_new_bal := v_prev_bal + CASE WHEN v_line.entry_type='DEBIT' THEN v_line.amount ELSE -v_line.amount END;
END IF;
```

### 5.4 Tại sao cùng vế thì cùng hành vi?

Quay lại phương trình:

```
ASSET + EXPENSE = LIABILITY + EQUITY + REVENUE
```

Giả sử một giao dịch chỉ ảnh hưởng đến 2 tài khoản (luôn luôn đúng trong double-entry). Có 2 trường hợp:

**Trường hợp 1: Hai tài khoản ở 2 vế khác nhau (cả hai đều TĂNG hoặc cả hai đều GIẢM)**

Ví dụ: Khách nạp 100k. Asset tăng 100k, Liability tăng 100k.

```
(ASSET + 100k) + EXPENSE = (LIABILITY + 100k) + EQUITY + REVENUE
```

Cả 2 vế đều tăng 100k → Phương trình vẫn cân bằng. Nhưng vì 2 tài khoản ở 2 vế khác nhau, "tăng" phải được gắn nhãn khác nhau để phân biệt:
- Asset (vế trái) tăng → Gắn nhãn **DEBIT**
- Liability (vế phải) tăng → Gắn nhãn **CREDIT**

**Trường hợp 2: Hai tài khoản ở cùng vế (một tăng, một giảm)**

Ví dụ: Trả phí AWS 50k. Asset giảm 50k, Expense tăng 50k.

```
(ASSET − 50k) + (EXPENSE + 50k) = LIABILITY + EQUITY + REVENUE
```

Vế trái tự triệt tiêu (−50k +50k = 0) → Phương trình vẫn cân bằng. Cả hai ở cùng vế trái nên:
- Expense tăng → Gắn nhãn **DEBIT** (vế trái tăng = Debit)
- Asset giảm → Gắn nhãn **CREDIT** (vế trái giảm = Credit)

---

## 6. Double-Entry: Luật chốt chặn

### 6.1 Quy tắc Golden Rule

Trong **MỌI** giao dịch:

```
Tổng giá trị gắn nhãn DEBIT  =  Tổng giá trị gắn nhãn CREDIT
```

**Không có ngoại lệ. Nếu không bằng nhau, giao dịch bị reject.**

Đây chính là constraint được enforce trong database trigger (tham chiếu `07-data-architecture.md`):

```sql
-- Trích từ trg_verify_double_entry_statement
HAVING COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0) != 0
-- Nếu tổng != 0 → RAISE EXCEPTION (reject toàn bộ transaction)
```

### 6.2 Tại sao luật này luôn đúng?

Vì mỗi giao dịch **phải giữ phương trình cân bằng**. Bất kỳ thay đổi nào ở vế trái phải có thay đổi tương ứng ở vế phải (hoặc triệt tiêu nội bộ cùng vế). Nhãn Debit/Credit chính là cơ chế đảm bảo điều đó.

---

## 7. Ví dụ thực tế trong Payment System

### 7.1 Khách hàng nạp tiền vào ví (Top-up 500k qua VNPay)

| Bước | Account | Type | Entry | Amount | Giải thích |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `company_vnpay_bank` | ASSET | **DEBIT** | 500,000 | Tiền thật chảy vào bank VNPay của công ty (vế trái tăng) |
| 2 | `user_wallet_A` | LIABILITY | **CREDIT** | 500,000 | Công ty nợ khách A thêm 500k (vế phải tăng) |

```
Kiểm tra: DEBIT 500k = CREDIT 500k ✅
Phương trình: (ASSET+500k) = (LIABILITY+500k) + EQUITY + REVENUE ✅
```

### 7.2 Khách mua hàng trên App bằng ví (Mua ly cà phê 30k)

| Bước | Account | Type | Entry | Amount | Giải thích |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `user_wallet_A` | LIABILITY | **DEBIT** | 30,000 | Trừ nợ khách A 30k (vế phải giảm = Debit) |
| 2 | `transaction_fee_revenue` | REVENUE | **CREDIT** | 30,000 | Công ty ghi nhận doanh thu 30k (vế phải tăng = Credit) |

```
Kiểm tra: DEBIT 30k = CREDIT 30k ✅
Phương trình: ASSET = (LIABILITY−30k) + EQUITY + (REVENUE+30k) ✅ (vế phải triệt tiêu)
```

### 7.3 Trả phí Gateway cho VNPay (Phí xử lý giao dịch 2k)

| Bước | Account | Type | Entry | Amount | Giải thích |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `gateway_processing_expense` | EXPENSE | **DEBIT** | 2,000 | Chi phí phát sinh (vế trái tăng) |
| 2 | `company_vnpay_bank` | ASSET | **CREDIT** | 2,000 | Trừ tiền từ bank VNPay (vế trái giảm) |

```
Kiểm tra: DEBIT 2k = CREDIT 2k ✅
Phương trình: (ASSET−2k) + (EXPENSE+2k) = LIABILITY + EQUITY + REVENUE ✅ (vế trái triệt tiêu)
```

### 7.4 Khách yêu cầu hoàn tiền (Refund 30k về ví)

| Bước | Account | Type | Entry | Amount | Giải thích |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `transaction_fee_revenue` | REVENUE | **DEBIT** | 30,000 | Giảm doanh thu (vế phải giảm = Debit) |
| 2 | `user_wallet_A` | LIABILITY | **CREDIT** | 30,000 | Tăng nợ khách A trở lại (vế phải tăng = Credit) |

```
Kiểm tra: DEBIT 30k = CREDIT 30k ✅
Phương trình: ASSET = LIABILITY+30k + EQUITY + (REVENUE−30k) ✅ (vế phải triệt tiêu)
```

---

## 8. Ánh xạ vào Database Schema

Phương trình kế toán ánh xạ trực tiếp vào schema `accounts` trong `07-data-architecture.md`:

```sql
CREATE TABLE accounts (
    account_id      VARCHAR(255) PRIMARY KEY,
    user_id         VARCHAR(255),
    -- Loại tài khoản: xác định nó nằm ở VẾ nào của phương trình
    account_type    VARCHAR(20) NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    -- Normal balance: xác định Debit hay Credit làm TĂNG tài khoản này
    -- Vế trái (ASSET, EXPENSE)  → normal_balance = 'DEBIT'
    -- Vế phải (LIABILITY, EQUITY, REVENUE) → normal_balance = 'CREDIT'
    normal_balance  VARCHAR(6) NOT NULL
        CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    allow_negative  BOOLEAN NOT NULL DEFAULT FALSE,
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Ví dụ dữ liệu seed accounts:

```sql
-- VẾ TRÁI: normal_balance = 'DEBIT'
INSERT INTO accounts VALUES ('company_vnpay_bank',    NULL,      'ASSET',     'DEBIT',  FALSE, 'VND', NOW());
INSERT INTO accounts VALUES ('gateway_fee_expense',   NULL,      'EXPENSE',   'DEBIT',  FALSE, 'VND', NOW());

-- VẾ PHẢI: normal_balance = 'CREDIT'
INSERT INTO accounts VALUES ('user_wallet_001',       'user001', 'LIABILITY', 'CREDIT', FALSE, 'VND', NOW());
INSERT INTO accounts VALUES ('owner_capital',         NULL,      'EQUITY',    'CREDIT', FALSE, 'VND', NOW());
INSERT INTO accounts VALUES ('txn_fee_revenue',       NULL,      'REVENUE',   'CREDIT', FALSE, 'VND', NOW());
```

---

## 9. Công thức tính Balance bằng TypeScript (Application Layer)

```typescript
type AccountType = 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE';
type EntryType = 'DEBIT' | 'CREDIT';
type NormalBalance = 'DEBIT' | 'CREDIT';

/**
 * Xác định normal_balance dựa trên account_type.
 * Vế trái phương trình (ASSET, EXPENSE) → DEBIT
 * Vế phải phương trình (LIABILITY, EQUITY, REVENUE) → CREDIT
 */
function getNormalBalance(accountType: AccountType): NormalBalance {
  return ['ASSET', 'EXPENSE'].includes(accountType) ? 'DEBIT' : 'CREDIT';
}

/**
 * Tính hiệu ứng của một bút toán lên số dư tài khoản.
 * Nếu entryType trùng với normalBalance → Cộng thêm (+amount)
 * Nếu entryType ngược với normalBalance → Trừ đi (-amount)
 */
function getBalanceEffect(
  accountType: AccountType,
  entryType: EntryType,
  amount: number
): number {
  const normalBalance = getNormalBalance(accountType);
  return entryType === normalBalance ? amount : -amount;
}

// --- Ví dụ sử dụng ---

// Ví khách hàng (LIABILITY) được CREDIT 500k → Tăng 500k
getBalanceEffect('LIABILITY', 'CREDIT', 500_000);  // → +500,000

// Ví khách hàng (LIABILITY) bị DEBIT 30k → Giảm 30k
getBalanceEffect('LIABILITY', 'DEBIT', 30_000);    // → -30,000

// Bank công ty (ASSET) được DEBIT 500k → Tăng 500k
getBalanceEffect('ASSET', 'DEBIT', 500_000);       // → +500,000

// Bank công ty (ASSET) bị CREDIT 2k → Giảm 2k
getBalanceEffect('ASSET', 'CREDIT', 2_000);        // → -2,000
```

---

## 10. Tóm tắt

1. **Phương trình gốc**: `ASSET = LIABILITY + EQUITY` (Cái hộp chứa gì = Ai cung cấp)
2. **Revenue tăng Equity, Expense giảm Equity** → Tách ra thành phương trình mở rộng
3. **Phương trình mở rộng**: `ASSET + EXPENSE = LIABILITY + EQUITY + REVENUE`
4. **Debit/Credit là quy ước**: Vế trái tăng bằng Debit, vế phải tăng bằng Credit
5. **Normal Balance**: Chiều (DR/CR) làm tăng giá trị — quyết định bởi vị trí trong phương trình
6. **Golden Rule**: Mọi giao dịch luôn có Tổng Debit = Tổng Credit → Phương trình luôn cân bằng
7. **Không có logic triết học sâu xa**: Đây là một hệ thống quy ước (convention) 500+ năm tuổi, được thiết kế để đảm bảo tính toàn vẹn dữ liệu tài chính

---

> **Tham chiếu**: Xem chi tiết schema và stored procedure tại [07-data-architecture.md](./07-data-architecture.md)
