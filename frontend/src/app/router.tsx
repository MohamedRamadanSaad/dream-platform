import { useEffect } from 'react'
import { createBrowserRouter, Navigate, Outlet, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { meApi } from '@/api/endpoints'
import { Skeleton } from '@/components/ui'
import { useAuthStore, isInterpreter } from './auth-store'
import { AppShell } from '@/components/layout'
import LandingPage from '@/features/public/LandingPage'
import LoginPage, { OnboardingPage, MagicCallbackPage } from '@/features/auth/LoginPage'
import { StaticPage } from '@/features/public/StaticPage'
import { UserDreamsPage } from '@/features/user/DreamsPage'
import { NewDreamPage } from '@/features/user/NewDreamPage'
import { DreamDetailPage } from '@/features/user/DreamDetailPage'
import { PackagesPage, CheckoutPage, MockCheckoutPage } from '@/features/user/CheckoutPage'
import { PaymentsPage } from '@/features/user/PaymentsPage'
import { NotificationsPage } from '@/features/user/NotificationsPage'
import { ProfilePage } from '@/features/account/ProfilePage'
import { CoursesPage } from '@/features/user/CoursesPage'
import { AdminDashboard } from '@/features/admin/Dashboard'
import { AdminQueuePage } from '@/features/admin/QueuePage'
import { AdminDreamPage } from '@/features/admin/DreamPage'
import { GeoPricingPage } from '@/features/admin/GeoPricingPage'
import { PackagesAdminPage } from '@/features/admin/PackagesAdminPage'
import { WaitTimePage } from '@/features/admin/WaitTimePage'
import { UsersPage, User360Page } from '@/features/admin/UsersPage'
import { TestimonialsAdminPage } from '@/features/admin/TestimonialsAdminPage'
import { OrdersAdminPage } from '@/features/admin/OrdersAdminPage'
import { EmailSettingsPage } from '@/features/admin/EmailSettingsPage'
import { PasskeyOffer } from '@/features/account/PasskeyOffer'
import { usePageTracking } from './usePageTracking'

/** Root of every route: page-view tracking lives here so each pathname change is counted once. */
function RootShell() {
  usePageTracking()
  return <Outlet />
}

function QueueAlias() {
  const { search } = useLocation()
  return <Navigate to={`/admin/dreams${search}`} replace />
}

/**
 * Where a signed-in account lands when the URL belongs to the other role: its own home, or its own account page
 * (keeping #devices) when the URL was the other role's account page.
 */
const HOME_OF = { USER: '/me', INTERPRETER: '/admin' } as const
const PROFILE_OF = { USER: '/me/profile', INTERPRETER: '/admin/profile' } as const
function landingOf(role: 'USER' | 'INTERPRETER', loc: { pathname: string; hash: string }) {
  const other = PROFILE_OF[role === 'USER' ? 'INTERPRETER' : 'USER']
  return loc.pathname.replace(/\/$/, '') === other ? PROFILE_OF[role] + loc.hash : HOME_OF[role]
}

/**
 * Route guard. The user saved in the browser is only a cache: the server's answer (GET /me, role read from the
 * database) decides. Interpreter pages never render before the server confirms the INTERPRETER role, so typing an
 * /admin URL or editing local storage cannot open them (the API refuses non-interpreters with 403 anyway).
 */
function RequireAuth({ role }: { role?: 'USER' | 'INTERPRETER' }) {
  const user = useAuthStore((s) => s.user)
  const setUser = useAuthStore((s) => s.setUser)
  const loc = useLocation()
  const me = useQuery({ queryKey: ['me', 'guard'], queryFn: meApi.get, enabled: !!user, staleTime: 30_000, retry: false })
  useEffect(() => { if (me.data) setUser(me.data) }, [me.data, setUser])
  // the hash survives the sign-in (e.g. #devices from the new sign-in e-mail)
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search + loc.hash)}`} replace />
  if (role === 'INTERPRETER') {
    if (me.isError) return <Navigate to="/me" replace />
    if (!me.data) return <div className="mx-auto max-w-6xl space-y-4 px-4 py-10"><Skeleton className="h-10 w-1/3" /><Skeleton className="h-64" /></div>
    if (!isInterpreter(me.data)) return <Navigate to={landingOf('USER', loc)} replace />
  }
  const current = me.data ?? user
  if (!current.onboarded && loc.pathname !== '/onboarding') return <Navigate to="/onboarding" replace />
  if (role === 'USER' && isInterpreter(current)) return <Navigate to={landingOf('INTERPRETER', loc)} replace />
  return <Outlet />
}

function UserLayout() {
  const { t } = useTranslation()
  return (
    <AppShell items={[
      { to: '/me', label: t('me.nav.dreams'), icon: 'moon', end: true },
      { to: '/me/new', label: t('me.nav.new'), icon: 'plus' },
      { to: '/me/packages', label: t('me.nav.packages'), icon: 'gift' },
      { to: '/me/payments', label: t('me.nav.payments'), icon: 'wallet' },
      { to: '/me/profile', label: t('me.nav.profile'), icon: 'user' },
      { to: '/me/courses', label: t('me.nav.courses'), icon: 'play' },
      { to: '/me/notifications', label: t('me.nav.notifications'), icon: 'bell' },
    ]}><PasskeyOffer /><Outlet /></AppShell>
  )
}

function AdminLayout() {
  const { t } = useTranslation()
  return (
    <AppShell admin items={[
      { to: '/admin', label: t('admin.nav.dashboard'), icon: 'chart', end: true },
      { to: '/admin/dreams', label: t('admin.nav.dreams'), icon: 'moon' },
      { to: '/admin/users', label: t('admin.nav.users'), icon: 'user' },
      { to: '/admin/pricing', label: t('admin.nav.pricing'), icon: 'globe' },
      { to: '/admin/packages', label: t('admin.nav.packages'), icon: 'gift' },
      { to: '/admin/wait-time', label: t('admin.nav.waitTime'), icon: 'clock' },
      { to: '/admin/testimonials', label: t('admin.nav.testimonials'), icon: 'quote' },
      { to: '/admin/orders', label: t('admin.nav.orders'), icon: 'wallet' },
      { to: '/admin/emails', label: t('admin.nav.emails'), icon: 'mail' },
      { to: '/admin/notifications', label: t('me.nav.notifications'), icon: 'bell' },
      { to: '/admin/profile', label: t('admin.nav.profile'), icon: 'account' },
    ]}><PasskeyOffer /><Outlet /></AppShell>
  )
}

export const router = createBrowserRouter([{ element: <RootShell />, children: [
  { path: '/', element: <LandingPage /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/auth/callback', element: <MagicCallbackPage /> },
  { path: '/terms', element: <StaticPage kind="terms" /> },
  { path: '/privacy', element: <StaticPage kind="privacy" /> },
  { path: '/courses', element: <CoursesPage publicView /> },
  { path: '/checkout/mock/:orderId', element: <MockCheckoutPage /> },
  {
    element: <RequireAuth />, children: [{ path: '/onboarding', element: <OnboardingPage /> }],
  },
  {
    element: <RequireAuth role="USER" />,
    children: [{
      path: '/me', element: <UserLayout />, children: [
        { index: true, element: <UserDreamsPage /> },
        { path: 'new', element: <NewDreamPage /> },
        { path: 'dreams/:id', element: <DreamDetailPage /> },
        { path: 'dreams/:id/edit', element: <NewDreamPage /> },
        { path: 'packages', element: <PackagesPage /> },
        { path: 'checkout', element: <CheckoutPage /> },
        { path: 'payments', element: <PaymentsPage /> },
        { path: 'notifications', element: <NotificationsPage /> },
        { path: 'profile', element: <ProfilePage /> },
        { path: 'courses', element: <CoursesPage /> },
      ],
    }],
  },
  {
    element: <RequireAuth role="INTERPRETER" />,
    children: [{
      path: '/admin', element: <AdminLayout />, children: [
        { index: true, element: <AdminDashboard /> },
        { path: 'dreams', element: <AdminQueuePage /> },
        { path: 'dreams/:id', element: <AdminDreamPage /> },
        { path: 'pricing', element: <GeoPricingPage /> },
        { path: 'packages', element: <PackagesAdminPage /> },
        { path: 'wait-time', element: <WaitTimePage /> },
        { path: 'users', element: <UsersPage /> },
        { path: 'users/:id', element: <User360Page /> },
        { path: 'testimonials', element: <TestimonialsAdminPage /> },
        { path: 'orders', element: <OrdersAdminPage /> },
        { path: 'emails', element: <EmailSettingsPage /> },
        { path: 'notifications', element: <NotificationsPage /> },
        { path: 'profile', element: <ProfilePage /> },
        // server-side insight links point at /admin/queue — the queue lives at /admin/dreams
        { path: 'queue', element: <QueueAlias /> },
      ],
    }],
  },
  { path: '*', element: <Navigate to="/" replace /> },
] }])
