import { createBrowserRouter, Navigate } from 'react-router'
import { Layout } from './components/Layout'
import { EventDetailPage } from './pages/EventDetailPage'
import { EventListPage } from './pages/EventListPage'
import { NotFoundPage } from './pages/NotFoundPage'

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/events" replace /> },
      { path: 'events', element: <EventListPage /> },
      { path: 'events/:eventId', element: <EventDetailPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])