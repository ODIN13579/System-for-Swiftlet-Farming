import { useEffect, useState, type FormEvent } from 'react'
import { RefreshCw, UserPlus, Users, ShieldCheck } from 'lucide-react'
import {
  api,
  type Account,
  type CreateAccountInput,
} from '../services/api'

type HouseOption = {
  id: number
  name?: string
  houseName?: string
  active?: boolean
}

const PAGE_SIZE = 20

export default function AdminUsers() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [houses, setHouses] = useState<HouseOption[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [phone, setPhone] = useState('')
  const [role, setRole] = useState<'OWNER' | 'ADMIN'>('OWNER')
  const [houseId, setHouseId] = useState('')
  const [houseRole, setHouseRole] = useState<'OWNER' | 'ADMIN' | 'VIEWER'>(
    'VIEWER',
  )

  async function loadAccounts(targetPage = page) {
    setLoading(true)
    setError('')

    try {
      const result = await api.getAccounts(targetPage, PAGE_SIZE)
      setAccounts(result.items ?? [])
      setTotalPages(Math.max(1, result.page?.totalPages ?? 1))
      setPage(targetPage)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tải danh sách tài khoản.',
      )
    } finally {
      setLoading(false)
    }
  }

  async function loadHouses() {
    try {
      const result = await api.getHouses(0, 100)
      setHouses((result.items ?? []) as HouseOption[])
    } catch {
      // Danh sách tài khoản vẫn sử dụng được nếu không tải được danh sách nhà.
      setHouses([])
    }
  }

  useEffect(() => {
    void loadAccounts(0)
    void loadHouses()
  }, [])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setMessage('')

    if (password.length < 12 || !/[A-Za-z]/.test(password) || !/\d/.test(password)) {
      setError('Mật khẩu phải có ít nhất 12 ký tự, bao gồm chữ cái và chữ số.')
      return
    }

    const payload: CreateAccountInput = {
      email: email.trim(),
      password,
      role,
      fullName: fullName.trim() || undefined,
      phone: phone.trim() || undefined,
    }

    if (houseId) {
      payload.houseId = Number(houseId)
      payload.houseRole = houseRole
    }

    setSaving(true)

    try {
      await api.createAccount(payload)
      setMessage('Tạo tài khoản thành công.')
      setEmail('')
      setPassword('')
      setFullName('')
      setPhone('')
      setRole('OWNER')
      setHouseId('')
      setHouseRole('VIEWER')
      await loadAccounts(0)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể tạo tài khoản.',
      )
    } finally {
      setSaving(false)
    }
  }

  async function handleToggleActive(account: Account) {
    const action = account.active ? 'vô hiệu hóa' : 'kích hoạt'
    const confirmed = window.confirm(
      `Bạn có chắc muốn ${action} tài khoản ${account.email}?`,
    )

    if (!confirmed) return

    setError('')
    setMessage('')

    try {
      await api.setAccountActive(account.id, !account.active)
      setMessage(
        `Đã ${account.active ? 'vô hiệu hóa' : 'kích hoạt'} tài khoản ${account.email}.`,
      )
      await loadAccounts(page)
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : 'Không thể cập nhật trạng thái tài khoản.',
      )
    }
  }

  return (
    <main className="admin-users-page">
      <header className="admin-users-heading">
        <div>
          <h1>Quản lý tài khoản</h1>
          <p>Tạo tài khoản, phân quyền và quản lý trạng thái hoạt động.</p>
        </div>

        <button
          type="button"
          className="admin-secondary-button"
          onClick={() => {
            void loadAccounts(page)
            void loadHouses()
          }}
          disabled={loading}
        >
          <RefreshCw size={16} />
          Làm mới
        </button>
      </header>

      {error && <div className="admin-message admin-message-error">{error}</div>}
      {message && (
        <div className="admin-message admin-message-success">{message}</div>
      )}

      <section className="admin-users-card">
        <div className="admin-card-heading">
          <UserPlus size={20} />
          <div>
            <h2>Tạo tài khoản mới</h2>
            <p>Có thể gán tài khoản vào một nhà ngay khi tạo.</p>
          </div>
        </div>

        <form className="admin-create-form" onSubmit={handleCreate}>
          <label>
            Email đăng nhập *
            <input
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              maxLength={254}
              autoComplete="email"
            />
          </label>

          <label>
            Mật khẩu *
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              minLength={12}
              autoComplete="new-password"
            />
            <small>Tối thiểu 12 ký tự, có chữ cái và chữ số.</small>
          </label>

          <label>
            Họ và tên
            <input
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              maxLength={150}
              autoComplete="name"
            />
          </label>

          <label>
            Số điện thoại
            <input
              type="tel"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              maxLength={30}
              autoComplete="tel"
            />
          </label>

          <label>
            Vai trò hệ thống *
            <select
              value={role}
              onChange={(event) =>
                setRole(event.target.value as 'OWNER' | 'ADMIN')
              }
            >
              <option value="OWNER">OWNER — Chủ sở hữu</option>
              <option value="ADMIN">ADMIN — Quản trị viên</option>
            </select>
          </label>

          <label>
            Nhà được gán (không bắt buộc)
            <select
              value={houseId}
              onChange={(event) => setHouseId(event.target.value)}
            >
              <option value="">Không gán nhà</option>
              {houses
                .filter((house) => house.active !== false)
                .map((house) => (
                  <option key={house.id} value={house.id}>
                    {house.name ?? house.houseName ?? `Nhà #${house.id}`}
                  </option>
                ))}
            </select>
          </label>

          {houseId && (
            <label>
              Vai trò tại nhà *
              <select
                value={houseRole}
                onChange={(event) =>
                  setHouseRole(
                    event.target.value as 'OWNER' | 'ADMIN' | 'VIEWER',
                  )
                }
              >
                <option value="OWNER">OWNER — Chủ nhà</option>
                <option value="ADMIN">ADMIN — Quản trị nhà</option>
                <option value="VIEWER">VIEWER — Chỉ xem</option>
              </select>
            </label>
          )}

          <div className="admin-form-actions">
            <button
              type="submit"
              className="admin-primary-button"
              disabled={saving}
            >
              <UserPlus size={16} />
              {saving ? 'Đang tạo...' : 'Tạo tài khoản'}
            </button>
          </div>
        </form>
      </section>

      <section className="admin-users-card">
        <div className="admin-card-heading">
          <Users size={20} />
          <div>
            <h2>Danh sách tài khoản</h2>
            <p>Trang {page + 1} / {totalPages}</p>
          </div>
        </div>

        {loading ? (
          <p className="admin-empty-state">Đang tải danh sách tài khoản...</p>
        ) : accounts.length === 0 ? (
          <p className="admin-empty-state">Chưa có tài khoản trong trang này.</p>
        ) : (
          <div className="admin-table-wrapper">
            <table className="admin-users-table">
              <thead>
                <tr>
                  <th>Tài khoản</th>
                  <th>Họ và tên</th>
                  <th>Điện thoại</th>
                  <th>Vai trò</th>
                  <th>Trạng thái</th>
                  <th>Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr key={account.id}>
                    <td>{account.email}</td>
                    <td>{account.fullName || '—'}</td>
                    <td>{account.phone || '—'}</td>
                    <td>
                      <span className="admin-role-label">
                        <ShieldCheck size={14} />
                        {account.role}
                      </span>
                    </td>
                    <td>
                      <span
                        className={
                          account.active
                            ? 'admin-status admin-status-active'
                            : 'admin-status admin-status-inactive'
                        }
                      >
                        {account.active ? 'Hoạt động' : 'Đã vô hiệu hóa'}
                      </span>
                    </td>
                    <td>
                      <button
                        type="button"
                        className={
                          account.active
                            ? 'admin-danger-button'
                            : 'admin-secondary-button'
                        }
                        onClick={() => void handleToggleActive(account)}
                      >
                        {account.active ? 'Vô hiệu hóa' : 'Kích hoạt'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="admin-pagination">
          <button
            type="button"
            className="admin-secondary-button"
            disabled={loading || page <= 0}
            onClick={() => void loadAccounts(page - 1)}
          >
            Trang trước
          </button>
          <span>{page + 1} / {totalPages}</span>
          <button
            type="button"
            className="admin-secondary-button"
            disabled={loading || page + 1 >= totalPages}
            onClick={() => void loadAccounts(page + 1)}
          >
            Trang sau
          </button>
        </div>
      </section>
    </main>
  )
}
