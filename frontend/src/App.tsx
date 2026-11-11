import { useEffect, useState } from 'react'
import {
  LayoutDashboard,
  Thermometer,
  Bell,
  ChartNoAxesCombined,
  Bird,
  Menu,
  LogOut,
  ChevronRight,
  Wifi,
  SlidersHorizontal,
  Home,
  Layers,
} from 'lucide-react'
import './App.css'
import Dashboard from './pages/Dashboard'
import Devices from './pages/Devices'
import Alerts from './pages/Alerts'
import Reports from './pages/Reports'
import Login from './pages/Login'
import ZoneConfig from './pages/ZoneConfig'
import Houses from './pages/Houses'
import Zones from './pages/Zones'
import { setAccessToken } from './services/api'
type Page =
  | 'dashboard'
  | 'houses'
  | 'zones'
  | 'environment'
  | 'zone-config'
  | 'alerts'
  | 'reports'
const menuItems = [
  { id: 'dashboard' as const, label: 'Tổng quan', icon: LayoutDashboard },
  { id: 'environment' as const, label: 'Môi trường & Thiết bị', icon: Thermometer },
  { id: 'alerts' as const, label: 'Cảnh báo & AI Events', icon: Bell },
  { id: 'reports' as const, label: 'Thống kê & Báo cáo', icon: ChartNoAxesCombined },
  { id: 'houses' as const, label: 'Nhà yến', icon: Home },
  { id: 'zones' as const, label: 'Khu vực', icon: Layers },
  {
  id: 'zone-config' as const,
  label: 'Cấu hình ngưỡng',
  icon: SlidersHorizontal,
  },
]
function App() {
  
  const [activePage, setActivePage] = useState<Page>('dashboard')

  const [isMobile, setIsMobile] = useState(
    () => window.matchMedia('(max-width: 650px)').matches
  )

  const [sidebarOpen, setSidebarOpen] = useState(
    () => !window.matchMedia('(max-width: 650px)').matches
  )

  useEffect(() => {
    const mediaQuery = window.matchMedia('(max-width: 650px)')

    const handleScreenChange = (event: MediaQueryListEvent) => {
      setIsMobile(event.matches)
      setSidebarOpen(!event.matches)
    }

    mediaQuery.addEventListener('change', handleScreenChange)

    return () => {
      mediaQuery.removeEventListener('change', handleScreenChange)
    }
  }, [])

  const currentPage = menuItems.find((item) => item.id === activePage)
  const [authenticated, setAuthenticated] = useState(false)
  function handleLogin(token: string) {
    setAccessToken(token)
    setAuthenticated(true)
  }

  function handleLogout() {
    setAccessToken(null)
    setAuthenticated(false)
    setActivePage('dashboard')
  }
  
  if (!authenticated) {
    return <Login onLogin={handleLogin} />
  }
  return (
    <div className="app-shell">
      <aside
        className={`sidebar ${sidebarOpen ? '' : 'sidebar-collapsed'}`}
        aria-label="Menu điều hướng"
        aria-hidden={isMobile && !sidebarOpen}
      >
        <div className="brand">
          <div className="brand-icon"><Bird size={25} /></div>
          {sidebarOpen && (
            <div className="brand-text">
              <strong>SwiftletCare</strong>
            
            </div>
          )}
        </div>
        <div className="sidebar-label">{sidebarOpen ? ' ' : 'MENU'}</div>

        <nav className="sidebar-nav">
          {menuItems.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.id}
                className={`nav-item ${activePage === item.id ? 'active' : ''}`}
                onClick={() => {
                  setActivePage(item.id)

                  if (isMobile) {
                    setSidebarOpen(false)
                  }
                }}
                title={item.label}
              >
                <Icon size={20} />
                {sidebarOpen && <span>{item.label}</span>}
                {sidebarOpen && activePage === item.id && <ChevronRight size={16} />}
              </button>
            )
          })}
        </nav>

        <div className="sidebar-bottom">
          <div className="connection-status">
            <span className="status-dot" />
            {sidebarOpen && <span>SwiftletCare</span>}
          </div>
          <button
            className="nav-item logout-button"
            onClick={handleLogout}
          >
            <LogOut size={19} />
            {sidebarOpen && <span>Đăng xuất</span>}
          </button>
        </div>
      </aside>
      {isMobile && sidebarOpen && (
        <button
          type="button"
          className="sidebar-backdrop"
          aria-label="Đóng menu"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      <main className="main-area">
        <header className="topbar">
          <button
            className="icon-button"
            onClick={() => setSidebarOpen(!sidebarOpen)}
            aria-label="Thu gọn hoặc mở rộng thanh bên"
          >
            <Menu size={21} />
          </button>

          <div className="breadcrumb">
            <span>SwiftletCare</span>
            <ChevronRight size={15} />
            <strong>{currentPage?.label ?? 'Tổng quan'}</strong>
          </div>

          <div className="topbar-right">
            <span className="online-label"><Wifi size={16} /> Chế độ Demo</span>
            <div className="avatar">A</div>
            <div className="user-label">
              <strong>Quản trị viên</strong>
              <span>Nhà yến thông minh</span>
            </div>
          </div>
        </header>

        <section className="page-content">
          {activePage === 'dashboard' && <Dashboard />}
          {activePage === 'environment' && <Devices />}          
          {activePage === 'alerts' && <Alerts />}
          {activePage === 'reports' && <Reports />}
          {activePage === 'zone-config' && <ZoneConfig />}
          {activePage === 'houses' && <Houses />}
          {activePage === 'zones' && <Zones />}
        </section>
      </main>
    </div>
  )
}
export default App
