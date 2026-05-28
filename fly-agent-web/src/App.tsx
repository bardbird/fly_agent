import { ChatLayout } from './components/layout/ChatLayout'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { SwePipelinePage } from '@/components/swe/SwePipelinePage'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ChatLayout />} />
        <Route path="/swe" element={<SwePipelinePage />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
