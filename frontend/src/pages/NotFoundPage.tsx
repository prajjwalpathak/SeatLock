import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <section className="py-16 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <Link to="/events" className="mt-4 inline-block text-indigo-600 hover:underline">
        Back to events
      </Link>
    </section>
  )
}